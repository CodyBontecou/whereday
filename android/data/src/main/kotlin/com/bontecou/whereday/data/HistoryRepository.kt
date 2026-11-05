package com.bontecou.whereday.data

import com.bontecou.whereday.domain.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import java.time.Clock
import java.time.Duration
import java.time.Instant

class VisitOverlap(val conflicting:Visit):IllegalStateException("Manual visit times overlap stored history.")
class VisitChanged:IllegalStateException("This visit changed. Close the editor and review the latest visit before trying again.")

class CountryStayChanged:IllegalStateException("This country stay changed. Close the editor and review the latest stay.")
class CountryStayFuture:IllegalArgumentException("Country stay dates must be today or earlier.")

class HistoryRepository(val database: HistoryDatabase, private val clock: Clock = Clock.systemUTC(),private val localZone:()->java.time.ZoneId={java.time.ZoneId.systemDefault()}) {
    val dao = database.history()
    private val lock = Mutex()
    private val detector = VisitDetector()

    suspend fun start(stopAfterHours: Int,startedBootCount:Int?=null): RecordingRow = lock.withLock {
        database.writeTransaction {
            val existing = dao.recording()
            if (existing?.requested == true && dao.openSessions().any { it.value.id == existing.sessionId }) return@writeTransaction existing
            val now = clock.instant()
            closeSessions(now)
            val session = RecordingSession(startedAt = now)
            dao.put(SessionRow(session))
            RecordingRow(requested = true, sessionId = session.id,startedBootCount=startedBootCount,
                deadline = stopAfterHours.takeIf { it > 0 }?.let { now.plus(Duration.ofHours(it.toLong())) }).also { dao.put(it) }
        }
    }

    suspend fun stop(reason: String? = null) = lock.withLock {
        database.writeTransaction {
            val now = clock.instant()
            val current=dao.recording() ?: RecordingRow()
            val evidence=if(reason==null) InterruptionEvidence(now,now) else interruptionEvidence(current)
            closeSessions(evidence.routeEnd)
            if(dao.visitMonitoring()?.requested!=true) {
                closeVisits(evidence.visitEnd, inferred = true)
                dao.put(DwellRow())
                clearVisitObservations()
            }
            dao.put(current.copy(requested = false, sessionId = null, deadline = null, interruption = reason))
        }
    }

    suspend fun ingest(points: List<TrackPoint>) = lock.withLock {
        database.writeTransaction {
            var recording = dao.recording() ?: return@writeTransaction
            if (!recording.requested) return@writeTransaction
            val session = dao.openSessions().firstOrNull { it.value.id == recording.sessionId }?.value ?: return@writeTransaction
            var state = dao.dwell()?.state ?: DwellState()
            var observations=dao.visitMonitoring() ?: VisitMonitoringRow()
            val webhookDao=database.webhooks()
            val webhook=webhookDao.policy()?.takeIf {it.enabled && it.capture}
            var recent = dao.latestSessionPoints(session.id).map { it.value }.sortedWith(compareBy({ it.time }, { it.id }))
            for (raw in points.sortedBy { it.time }) {
                if (!TrackQuality.accepts(raw) || recording.lastFix?.let { raw.time <= it } == true) continue
                if (raw.time < session.startedAt) continue
                if (recording.deadline?.let { raw.time > it } == true) continue
                if (raw.time > clock.instant().plusSeconds(60)) continue
                val newWebhookPoint=webhook!=null && dao.point(raw.id)==null
                val flagged = TrackQuality.flaggedIds(recent, raw)
                val point = raw.copy(outlier = raw.id in flagged)
                flagged.filterNot { it == raw.id }.forEach { id->
                    dao.flagPoint(id)
                    if(webhook!=null) {
                        if(webhook.includeOutliers) webhookDao.flagPending(webhook.revision,id) else webhookDao.removePending(webhook.revision,id)
                    }
                }
                dao.put(PointRow(point, recording.sessionId))
                if(newWebhookPoint && webhook!=null && (!point.outlier || webhook.includeOutliers)) webhookDao.capture(WebhookPointRow(webhook.revision,point,clock.instant()))
                if(observations.lastFix?.let {point.time<=it}!=true) {
                    state=consumeVisit(state,point)
                    observations=observations.observed(point)
                }
                recent = (recent + point).takeLast(2)
                recording = recording.copy(lastFix = point.time, interruption = null)
            }
            dao.put(DwellRow(state = state))
            dao.put(observations)
            dao.put(recording)
        }
    }

    /** Requested state is a durable intent, never evidence that a service is alive. */
    suspend fun startVisitMonitoring(startedBootCount:Int?=null):VisitMonitoringRow = lock.withLock {
        database.writeTransaction {
            val current=dao.visitMonitoring() ?: VisitMonitoringRow()
            if(current.requested) return@writeTransaction current
            current.copy(requested=true,generation=java.util.UUID.randomUUID().toString(),
                startedAt=clock.instant(),interruption=null,startedBootCount=startedBootCount).also {dao.put(it)}
        }
    }

    /** The lease prevents a terminated service/callback from stopping a replacement. */
    suspend fun stopVisitMonitoring(generation:String,reason:String?=null) = lock.withLock {
        database.writeTransaction {
            val current=dao.visitMonitoring() ?: return@writeTransaction
            if(!current.requested || current.generation!=generation) return@writeTransaction
            finishVisitMonitoring(current,reason)
        }
    }

    suspend fun interruptVisitMonitoring(reason:String,expected:VisitMonitoringRow?=null) = lock.withLock {
        database.writeTransaction {
            val current=dao.visitMonitoring() ?: return@writeTransaction
            if(expected!=null && current.generation!=expected.generation) return@writeTransaction
            if(current.requested) finishVisitMonitoring(current,reason)
        }
    }

    private suspend fun finishVisitMonitoring(current:VisitMonitoringRow,reason:String?) {
        dao.put(current.copy(requested=false,generation=null,startedAt=null,interruption=reason))
        if(dao.recording()?.requested!=true) {
            // Only the last supporting observation establishes presence at interruption/stop.
            dao.dwell()?.state?.lastTime?.let {closeVisits(it,true)}
            dao.put(DwellRow())
            clearVisitObservations()
        }
    }

    private fun VisitMonitoringRow.observed(point:TrackPoint)=copy(lastFix=point.time,
        previous=recent,recent=VisitObservation(point.coordinate,point.time,point.accuracy))

    private suspend fun clearVisitObservations() {
        dao.visitMonitoring()?.let {dao.put(it.copy(recent=VisitObservation(),previous=VisitObservation()))}
    }

    /** Balanced-power fixes feed visits only: no points, sessions or webhook capture.
     * A current route session owns observations during handoff; its own provider feeds this
     * same detector. Reject stale leases and batches before they can change any state. */
    suspend fun ingestVisits(generation:String,points:List<TrackPoint>) = lock.withLock {
        database.writeTransaction {
            var current=dao.visitMonitoring() ?: return@writeTransaction
            if(!current.requested || current.generation!=generation || dao.recording()?.requested==true) return@writeTransaction
            val now=clock.instant()
            var state=dao.dwell()?.state ?: DwellState()
            // Provider batches are small; bound work before sorting potentially hostile input.
            require(points.size<=64) {"Visit observation batch is too large."}
            for(raw in points.sortedBy {it.time}) {
                if(!TrackQuality.accepts(raw) || raw.time<requireNotNull(current.startedAt) ||
                    raw.time<now.minusSeconds(900) || raw.time>now.plusSeconds(60) ||
                    current.lastFix?.let {raw.time<=it}==true) continue
                val recent=listOfNotNull(current.previous.point(),current.recent.point())
                val point=raw.copy(outlier=raw.outlier || raw.id in TrackQuality.flaggedIds(recent,raw))
                state=consumeVisit(state,point)
                current=current.observed(point)
            }
            dao.put(DwellRow(state=state));dao.put(current)
        }
    }

    private suspend fun consumeVisit(state:DwellState,point:TrackPoint):DwellState {
        val transition = detector.consume(state, point)
        transition.departedId?.let { id -> dao.visit(id)?.let { row ->
            dao.put(VisitRow(row.value.copy(departedAt = maxOf(row.value.arrivedAt, transition.departedAt!!), departureInferred = true)))
        } }
        transition.arrived?.let { detected ->
            closeVisitsBefore(detected.arrivedAt)
            val match = dao.places().map { it.value }.filter { it.coordinate.distanceTo(detected.coordinate) <= it.radius }.minByOrNull { it.coordinate.distanceTo(detected.coordinate) }
            val visit = if (match == null) detected else detected.copy(name = match.name, address = match.address, confirmation = Confirmation.confirmed, placeSource = "userEntered", placeDistanceMeters = match.coordinate.distanceTo(detected.coordinate))
            val duplicate=duplicateVisit(visit,clock.instant(),priorOnly=false)
            val saved=duplicate?.let {VisitReconciliation.merge(it,visit)} ?: visit
            dao.put(VisitRow(saved))
            return transition.state.copy(visitId=saved.id)
        }
        return transition.state
    }

    /** Same chronological survivor and stale-open repair as iOS, with paged reads and one
     * transaction. Cancellation cannot leave partial closures/deletions or a dangling detector. */
    suspend fun reconcileVisits():Int = lock.withLock {
        database.writeTransaction {
            val now=clock.instant();var changed=0
            val latest=dao.latestOpenVisit()?.value
            if(latest!=null) {
                var after:Visit?=null
                while(true) {
                    currentCoroutineContext().ensureActive()
                    val page=dao.staleOpenVisitPage(latest.id,after?.arrivedAt,after?.id.orEmpty())
                    if(page.isEmpty()) break
                    for(row in page) {
                        val visit=row.value
                        dao.put(VisitRow(visit.copy(departedAt=VisitReconciliation.inferredDeparture(visit,dao.nextVisitArrival(visit.arrivedAt),now),departureInferred=true)))
                        changed++
                    }
                    after=page.last().value
                }
            }
            var page=dao.exportFirstVisits(Instant.ofEpochMilli(Long.MIN_VALUE),Instant.ofEpochMilli(Long.MAX_VALUE))
            var state=dao.dwell()?.state
            while(page.isNotEmpty()) {
                for(row in page) {
                    currentCoroutineContext().ensureActive()
                    val source=dao.visit(row.value.id)?.value ?: continue
                    val target=duplicateVisit(source,now,priorOnly=true) ?: continue
                    dao.put(VisitRow(VisitReconciliation.merge(target,source)));dao.deleteVisit(source.id)
                    if(state?.visitId==source.id) state=state!!.copy(visitId=target.id)
                    changed++
                }
                val last=page.last().value
                page=dao.exportNextVisits(last.arrivedAt,last.id,Instant.ofEpochMilli(Long.MAX_VALUE))
            }
            state?.let {saved->
                val owner=saved.visitId?.let {dao.visit(it)?.value}
                dao.put(DwellRow(state=if(saved.visitId!=null && (owner==null || owner.departedAt!=null)) DwellState() else saved))
            }
            currentCoroutineContext().ensureActive();changed
        }
    }

    private suspend fun closeVisitsBefore(arrival:Instant) {
        var after:Visit?=null
        while(true) {
            val page=dao.staleOpenVisitPage("",after?.arrivedAt,after?.id.orEmpty())
            if(page.isEmpty()) return
            for(row in page) if(row.value.arrivedAt<=arrival) dao.put(VisitRow(row.value.copy(departedAt=arrival,departureInferred=true)))
            after=page.last().value
            if(after.arrivedAt>arrival) return
            currentCoroutineContext().ensureActive()
        }
    }
    private suspend fun duplicateVisit(source:Visit,now:Instant,priorOnly:Boolean):Visit? {
        if(source.source!=VisitSource.automatic) return null
        fun shifted(at:Instant,seconds:Long)=at.plusSeconds(seconds).coerceIn(Instant.ofEpochMilli(Long.MIN_VALUE),Instant.ofEpochMilli(Long.MAX_VALUE))
        var after:Visit?=null
        val distance=SavedPlaceRepository(database)::distance
        while(true) {
            currentCoroutineContext().ensureActive()
            val page=dao.duplicateVisitCandidates(source.id,source.arrivedAt,priorOnly,
                maxOf(shifted(source.arrivedAt,900),shifted(source.departedAt ?: now,1800)),
                shifted(source.arrivedAt,-900),shifted(source.arrivedAt,-1800),now,
                source.coordinate.latitude-0.001,source.coordinate.latitude+0.001,after?.arrivedAt,after?.id.orEmpty())
            if(page.isEmpty()) return null
            page.firstOrNull {VisitReconciliation.shouldMerge(it.value,source,now,distance)}?.let {return it.value}
            after=page.last().value
        }
    }

    private fun persistedVisit(value:Visit)=value.copy(
        arrivedAt=Instant.ofEpochMilli(value.arrivedAt.toEpochMilli()),
        departedAt=value.departedAt?.let {Instant.ofEpochMilli(it.toEpochMilli())},
        confirmedAt=value.confirmedAt?.let {Instant.ofEpochMilli(it.toEpochMilli())},
        updatedAt=value.updatedAt?.let {Instant.ofEpochMilli(it.toEpochMilli())},
        countryResolutionAttemptedAt=value.countryResolutionAttemptedAt?.let {Instant.ofEpochMilli(it.toEpochMilli())}
    )

    suspend fun saveVisit(value:Visit,expected:Visit?,reuse:SavedPlaceReuseSave?=null,rememberConfirmedPlace:Boolean=false,allowOverlap:Boolean=false):Visit = lock.withLock {
        val visit=persistedVisit(value)
        val baseline=expected?.let(::persistedVisit)
        database.writeTransaction {
            require(expected==null || expected.id==visit.id) {"The visit identity changed."}
            require(!rememberConfirmedPlace || expected!=null && visit.confirmation!=Confirmation.unconfirmed) {"Only an explicitly reviewed existing visit can be remembered."}
            val current=dao.visit(visit.id)?.value
            var reusablePlan:SavedPlaceReusePlan?=null
            if(reuse!=null) {
                require(expected==null && visit.source==VisitSource.manual) {"Reusable-place creation is available for new manual visits."}
                require(reuse.candidate.name.trim()==visit.name && reuse.candidate.coordinate==visit.coordinate && reuse.candidate.address?.trim()?.ifEmpty {null}==visit.address) {"The reusable place must match this visit."}
                require(reuse.existing.map {it.id}.distinct().size==reuse.existing.size) {"The saved-place snapshot has duplicate identities."}
                val places=SavedPlaceRepository(database)
                val coroutine=currentCoroutineContext()
                reusablePlan=SavedPlaceReusePlan.build(reuse.candidate,reuse.existing,places::distance,places::nameKey) {coroutine.ensureActive()}
                val before=reuse.existing.associateBy {it.id}
                val after=before.toMutableMap().apply {put(reusablePlan.value.id,reusablePlan.value)}
                val actual=dao.places().associate {it.value.id to it.value}
                if(current==visit && actual==after) return@writeTransaction current
                if(current!=baseline) throw VisitChanged()
                if(actual!=before) throw SavedPlacesChanged()
            } else if(current==visit) return@writeTransaction current
            if(current!=baseline) throw VisitChanged()
            val now=clock.instant()
            require(value.arrivedAt<=now && (value.departedAt==null || value.departedAt in value.arrivedAt..now)) {"Enter valid past visit times."}
            require(baseline==null || visit.source==baseline.source) {"The visit source cannot change."}
            if(expected==null) require(visit.source==VisitSource.manual && !visit.name.isNullOrBlank() && visit.confirmation==Confirmation.confirmed) {"New visits must be named manual visits."}
            // iOS only asks again for creation or an actual manual time edit. Notes/place
            // changes can retain an intentionally overlapping interval without another prompt.
            if(!allowOverlap && visit.source==VisitSource.manual &&
                (baseline==null || visit.arrivedAt!=baseline.arrivedAt || visit.departedAt!=baseline.departedAt)) {
                val future=Instant.parse("4001-01-01T00:00:00Z")
                dao.manualVisitOverlap(visit.arrivedAt,visit.departedAt ?: future,future,visit.id)
                    ?.let {throw VisitOverlap(it.value)}
            }
            dao.put(VisitRow(visit))
            // A manually closed detector visit must not be reopened/overwritten by the next dwell sample.
            if(visit.departedAt!=null && dao.dwell()?.state?.visitId==visit.id) dao.put(DwellRow())
            reusablePlan?.let {dao.put(PlaceRow(it.value))}
            if(rememberConfirmedPlace) {
                val places=SavedPlaceRepository(database)
                val coroutine=currentCoroutineContext()
                confirmedPlaceToRemember(visit,dao.places().map {it.value},java.util.UUID.randomUUID().toString(),places::distance,places::nameKey) {coroutine.ensureActive()}
                    ?.let {dao.put(PlaceRow(it))}
            }
            currentCoroutineContext().ensureActive()
            visit
        }
    }

    /** Explicit automation check-in: independent of route recording and detector ownership. */
    suspend fun checkIn(name:String?,savedPlaceId:String?,coordinate:Coordinate?,arrivedAt:Instant,allowOverlap:Boolean=false):Visit = lock.withLock {
        database.writeTransaction {
            val now=clock.instant()
            require(arrivedAt<=now) {"Choose an arrival time at or before now."}
            require((savedPlaceId!=null) != (coordinate!=null)) {"Choose one saved place or coordinate."}
            val place=savedPlaceId?.let {dao.place(it)?.value ?: throw ManualVisitCommandFailed(ManualVisitProblem.missingPlace)}
            val visitName=name?.trim()?.takeIf {it.isNotEmpty()} ?: place?.name?.trim()
            require(!visitName.isNullOrBlank()) {"A place name is required."}
            val arrival=Instant.ofEpochMilli(arrivedAt.toEpochMilli())
            // Date.distantFuture is the actual sentinel used by the iOS manual mutation store.
            if(!allowOverlap && dao.manualVisitOverlap(arrival,Instant.parse("4001-01-01T00:00:00Z"),Instant.parse("4001-01-01T00:00:00Z"))!=null)
                throw ManualVisitCommandFailed(ManualVisitProblem.overlap)
            val stamp=Instant.ofEpochMilli(now.toEpochMilli())
            val visit=Visit(coordinate=place?.coordinate ?: requireNotNull(coordinate),arrivedAt=arrival,
                name=visitName,address=place?.address?.trim()?.takeIf {it.isNotEmpty()},source=VisitSource.manual,
                confirmation=Confirmation.confirmed,confirmedAt=stamp,updatedAt=stamp,placeSource="userEntered")
            currentCoroutineContext().ensureActive();dao.put(VisitRow(visit));currentCoroutineContext().ensureActive()
            visit
        }
    }

    /** Select, validate and close under one writer transaction; repeated checkout is explicit. */
    suspend fun checkOut(id:String?,departedAt:Instant?=null):Visit = lock.withLock {
        database.writeTransaction {
            val visit=if(id!=null) dao.visit(id)?.value ?: throw JournalItemNotFound() else {
                val candidates=dao.manualCheckoutCandidates()
                if(candidates.isEmpty()) throw ManualVisitCommandFailed(ManualVisitProblem.noOpenVisit)
                if(candidates.size>1) throw ManualVisitCommandFailed(ManualVisitProblem.ambiguousVisit)
                candidates.single().value
            }
            if(visit.source!=VisitSource.manual || visit.departedAt!=null)
                throw ManualVisitCommandFailed(ManualVisitProblem.closedVisit)
            val now=clock.instant();val departure=departedAt ?: now
            require(departure>=visit.arrivedAt && departure<=now) {"Choose a departure between arrival and now."}
            val closed=visit.copy(departedAt=Instant.ofEpochMilli(departure.toEpochMilli()),departureInferred=false,
                updatedAt=Instant.ofEpochMilli(now.toEpochMilli()))
            currentCoroutineContext().ensureActive();dao.put(VisitRow(closed));currentCoroutineContext().ensureActive()
            closed
        }
    }

    suspend fun deleteVisit(expected:Visit) = lock.withLock {
        database.writeTransaction {
            if(dao.visit(expected.id)?.value!=persistedVisit(expected)) throw VisitChanged()
            dao.deleteVisit(expected.id)
            if(dao.dwell()?.state?.visitId==expected.id) dao.put(DwellRow())
        }
    }

    suspend fun markInterrupted(reason: String,expected:RecordingRow?=null) = lock.withLock {
        database.writeTransaction {
            val current = dao.recording() ?: return@writeTransaction
            if(expected!=null && current.sessionId!=expected.sessionId) return@writeTransaction
            if (current.requested) {
                val evidence=interruptionEvidence(current)
                closeSessions(evidence.routeEnd)
                if(dao.visitMonitoring()?.requested!=true) {
                    closeVisits(evidence.visitEnd, true)
                    dao.put(DwellRow())
                    clearVisitObservations()
                }
                dao.put(current.copy(requested = false, sessionId = null, deadline = null, interruption = reason))
            }
        }
    }

    private data class InterruptionEvidence(val routeEnd:Instant,val visitEnd:Instant)
    private suspend fun interruptionEvidence(recording:RecordingRow):InterruptionEvidence {
        // Raw route history includes outliers. Only the detector's supporting observation
        // establishes presence at a visit; a failed start with no fixes adds no elapsed route.
        val routeEnd=recording.lastFix ?: recording.sessionId?.let {dao.session(it)?.value?.startedAt} ?: clock.instant()
        return InterruptionEvidence(routeEnd,dao.dwell()?.state?.lastTime ?: routeEnd)
    }

    /** Every visit arrival, for marking recorded days in the map date calendar. */
    fun observeVisitArrivals():Flow<List<Instant>> = dao.observeVisitArrivals()

    fun observeWindow(range:HistoryRange,inference:InferenceRules?=null,includeOutliers:Boolean=true):Flow<HistoryWindow> = observeWindow({range},inference,includeOutliers)
    /** Live map presets resolve their clock boundary again when durable history changes. */
    fun observeWindow(range:()->HistoryRange,inference:InferenceRules?=null,includeOutliers:Boolean=true):Flow<HistoryWindow> = database.invalidationTracker.createFlow("points","visits","sessions")
        .conflate().map { readWindow(range(),inference=inference,includeOutliers=includeOutliers) }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observePointMarkers(request:ReplayRequest,viewport:MapPointViewport,selectedPointId:String?):Flow<MapPointMarkers> =
        database.invalidationTracker.createFlow("points","sessions").conflate().mapLatest {readPointMarkers(request,viewport,selectedPointId)}

    /** Complete indexed history, independent of the route's uniform display sample. */
    suspend fun readPointMarkers(request:ReplayRequest,viewport:MapPointViewport,selectedPointId:String?=null):MapPointMarkers = database.readSnapshot {dao->
        val range=when(request) {
            is ReplayRequest.Range->request.range
            is ReplayRequest.Outing->{val session=dao.session(request.id)?.value ?: error("This outing was deleted")
                HistoryRange(session.startedAt,(session.endedAt ?: clock.instant()).coerceAtLeast(session.startedAt).plusMillis(1))}
            is ReplayRequest.Inferred->HistoryRange(request.header.startedAt,request.header.endedAt.plusMillis(1))
        }
        val sessions=if(request is ReplayRequest.Inferred) dao.rangeSessions(range.from,range.until).map {it.value} else emptyList()
        val good=MapPointMarkerSampler(viewport,selectedPointId);val all=MapPointMarkerSampler(viewport,selectedPointId)
        var hasGood=false
        forEachPoint(range) {point->
            if(sessions.none {point.time>=it.startedAt && (it.endedAt==null || point.time<=it.endedAt)}) {
                all.add(point)
                if(!point.outlier) {hasGood=true;good.add(point)}
            }
        }
        if((request as? ReplayRequest.Range)?.includeOutliers==true || (request !is ReplayRequest.Range && !hasGood)) all.snapshot() else good.snapshot()
    }

    fun observeActiveRecordingMap(includeOutliers:Boolean=false,displayLimit:Int=2500):Flow<ActiveRecordingMap?> =
        database.invalidationTracker.createFlow("points","sessions","recording").conflate().map {readActiveRecordingMap(includeOutliers,displayLimit)}

    /** Session-owned fixes only, from one WAL snapshot; filtering precedes bounded sampling. */
    suspend fun readActiveRecordingMap(includeOutliers:Boolean=false,displayLimit:Int=2500):ActiveRecordingMap? = database.readSnapshot {
        require(displayLimit in 2..2500)
        val recording=dao.recording()?.takeIf {it.requested} ?: return@readSnapshot null
        val session=recording.sessionId?.let {dao.session(it)?.value}?.takeIf {it.endedAt==null} ?: return@readSnapshot null
        val count=if(includeOutliers) dao.sessionPointCount(session.id) else dao.sessionGoodPointCount(session.id)
        val sampler=UniformPointSampler(count,displayLimit);val endpoints=RouteEndpointBuilder()
        val segments=mutableListOf<MutableList<TrackPoint>>()
        var previous:TrackPoint?=null;var gapPending=false
        var page=dao.sessionFirstPage(session.id)
        while(page.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            for(row in page) if(includeOutliers || !row.value.outlier) {
                val point=row.value
                if(previous?.let {Duration.between(it.time,point.time)>Duration.ofMinutes(10)}==true) gapPending=true
                if(sampler.addSelected(point)) {
                    if(segments.isEmpty() || gapPending) segments.add(mutableListOf())
                    segments.last().add(point);gapPending=false
                }
                endpoints.add(point);previous=point
            }
            val last=page.last().value
            page=dao.sessionNextPage(session.id,last.time,last.id)
        }
        ActiveRecordingMap(session,sampler.points(),count,endpoints.build(),segments.filter {it.size>1}.map {it.toList()})
    }

    /** Complete committed session metrics, independent of map samples and overlapping imports. */
    suspend fun sessionSummary(sessionId:String):PointSummary = database.readSnapshot {
        val summary=PointSummaryBuilder()
        var page=dao.sessionFirstPage(sessionId)
        while(page.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            page.forEach {summary.add(it.value)}
            val last=page.last().value
            page=dao.sessionNextPage(sessionId,last.time,last.id)
        }
        summary.build()
    }

    suspend fun saveCountryStay(value:CountryStay,expected:CountryStay?=null):CountryStay=lock.withLock {
        database.writeTransaction {
            require(value.id.isNotBlank() && (expected==null || expected.id==value.id))
            val today=java.time.LocalDate.now(clock.withZone(localZone()))
            if(value.lastDay>today) throw CountryStayFuture()
            val current=dao.stay(value.id)?.value
            if(current==value) return@writeTransaction value
            if(current!=expected) throw CountryStayChanged()
            currentCoroutineContext().ensureActive()
            dao.put(StayRow(value))
            currentCoroutineContext().ensureActive()
            value
        }
    }

    suspend fun deleteCountryStay(expected:CountryStay)=lock.withLock {
        database.writeTransaction {
            val current=dao.stay(expected.id)?.value ?: return@writeTransaction
            if(current!=expected) throw CountryStayChanged()
            currentCoroutineContext().ensureActive()
            dao.deleteStay(expected.id)
            currentCoroutineContext().ensureActive()
        }
    }

    fun observeCountryDays(bounds: CountryDayBounds): Flow<CountryDayReport> = database.invalidationTracker.createFlow("points","visits","country_stays")
        .conflate().map { readCountryDays(bounds) }

    suspend fun readCountryDays(bounds: CountryDayBounds = CountryDayBounds()): CountryDayReport = database.readSnapshot {
        val accumulator = CountryDayAccumulator(clock.instant(), bounds)
        val stays = dao.stays().map { it.value }
        dao.rangeVisits(HistoryRange.all.from, HistoryRange.all.until).forEach { accumulator.add(it.value) }
        forEachPoint(HistoryRange.all) { accumulator.add(it) }
        stays.forEach { accumulator.add(it) }
        CountryDayReport(accumulator.days(), stays, accumulator.unresolvedPoints, accumulator.unresolvedVisits)
    }

    suspend fun readWindow(range: HistoryRange, displayLimit: Int = 2500,inference: InferenceRules?=null,includeOutliers:Boolean=true): HistoryWindow = database.readSnapshot {
        val sessions=dao.rangeSessions(range.from,range.until).map { it.value }
        val inferred=inference?.let { StreamingOutingInference(sessions,it) }
        val (points,summary,endpoints)=sampleAndSummarize(range,displayLimit,includeOutliers=includeOutliers) { inferred?.add(it) }
        HistoryWindow(range,points,summary,dao.rangeVisits(range.from,range.until).map { it.value },sessions,inferred?.finish().orEmpty(),endpoints)
    }

    /** Per-day path distance and fix counts for a multi-day map range, streamed without keeping the fixes. */
    suspend fun readDayTotals(range:HistoryRange,zone:java.time.ZoneId):DayTotals = database.readSnapshot {
        val accumulator=DayDistanceAccumulator(zone)
        forEachPoint(range) {accumulator.add(it)}
        DayTotals(accumulator.distances(),accumulator.counts())
    }
    fun observeDayTotals(range:()->HistoryRange,zone:java.time.ZoneId):Flow<DayTotals> = database.invalidationTracker.createFlow("points")
        .conflate().map {readDayTotals(range(),zone)}

    /** UUID links may come from uppercase Swift or lowercase Android IDs. Never pick an ambiguous row. */
    suspend fun linkedVisit(id:String):Visit? = database.readSnapshot {
        require(java.util.UUID.fromString(id).toString().equals(id,ignoreCase=true))
        val rows=dao.visitLinkCandidates(id)
        require(rows.size<=1) {"Ambiguous visit identity"}
        rows.singleOrNull()?.value
    }

    suspend fun outingDetail(id: String, displayLimit: Int = 2500): OutingDetail = database.readSnapshot {
        val session=dao.session(id)?.value ?: error("This outing was deleted")
        val end=session.endedAt ?: clock.instant()
        val range=HistoryRange(session.startedAt,end.coerceAtLeast(session.startedAt).plusMillis(1))
        val (points,summary,endpoints)=sampleAndSummarize(range,displayLimit,includeOutliers=false,fallback=true)
        OutingDetail(session,points,summary,dao.rangeVisits(range.from,range.until).map { it.value },endpoints=endpoints)
    }

    private suspend fun sampleAndSummarize(range: HistoryRange, limit: Int,includeOutliers:Boolean=true,fallback:Boolean=false,each: (TrackPoint)->Unit={}): Triple<List<TrackPoint>,PointSummary,RouteEndpoints> {
        val goodCount=if(includeOutliers) 0L else dao.rangeGoodPointCount(range.from,range.until)
        val all=includeOutliers || (fallback && goodCount==0L)
        val sampler=UniformPointSampler(if(all) dao.rangePointCount(range.from,range.until) else goodCount,limit)
        val summary=PointSummaryBuilder();val endpoints=RouteEndpointBuilder()
        forEachPoint(range) { summary.add(it);if(all || !it.outlier) sampler.add(it); endpoints.add(it);each(it) }
        return Triple(sampler.points(),summary.build(),endpoints.build())
    }

    /** Indexed keyset pagination retains equal-timestamp fixes across every page boundary. */
    suspend fun forEachPoint(range: HistoryRange, consume: suspend (TrackPoint) -> Unit) {
        var page=dao.firstPage(range.from,range.until)
        while(page.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            page.forEach { consume(it.value) }
            val last=page.last().value
            page=dao.nextPage(last.time,last.id,range.until)
        }
    }

    suspend fun editSession(id: String, name: String?, notes: String?) = lock.withLock {
        database.writeTransaction {
            val old=dao.session(id)?.value ?: error("This outing was deleted")
            dao.put(SessionRow(old.copy(name=name?.trim()?.ifBlank { null },notes=notes?.ifBlank { null })))
        }
    }

    /** Rename only: do not overwrite notes, correction provenance or concurrent detector updates. */
    internal suspend fun renameVisit(id:String,name:String):Visit = lock.withLock {
        val trimmed=name.trim().also {require(it.isNotEmpty()) {"Enter a name."}}
        database.writeTransaction {
            val old=dao.visit(id)?.value ?: throw JournalItemNotFound()
            old.copy(name=trimmed,updatedAt=Instant.ofEpochMilli(clock.millis())).also {dao.put(VisitRow(it))}
        }
    }

    internal suspend fun renameOuting(id:String?,name:String):RecordingSession = lock.withLock {
        val trimmed=name.trim().also {require(it.isNotEmpty()) {"Enter a name."}}
        database.writeTransaction {
            val old=if(id==null) dao.latestOpenSession()?.value ?: throw NoCurrentOuting()
                else dao.session(id)?.value ?: throw JournalItemNotFound()
            old.copy(name=trimmed).also {dao.put(SessionRow(it))}
        }
    }

    /** Service shutdown is performed by the caller first when deleting the active outing. */
    suspend fun deleteSession(id: String) = lock.withLock {
        database.writeTransaction {
            val session=dao.session(id)?.value ?: return@writeTransaction
            val state=dao.recording()
            if(state?.sessionId==id) {
                if(dao.visitMonitoring()?.requested!=true) {
                    closeVisits(clock.instant(),true)
                    dao.put(DwellRow())
                    clearVisitObservations()
                }
                dao.put(state.copy(requested=false,sessionId=null,deadline=null))
            }
            dao.deletePointsInSessionWindow(session.startedAt,session.endedAt ?: clock.instant())
            dao.deleteSession(id)
        }
    }

    suspend fun inferredDetail(header: InferredOutingHeader,displayLimit: Int=2500): OutingDetail = database.readSnapshot {
        val range=HistoryRange(header.startedAt,header.endedAt.plusMillis(1))
        val sessions=dao.rangeSessions(range.from,range.until).map { it.value }
        fun unassigned(point: TrackPoint)=sessions.none { point.time>=it.startedAt && (it.endedAt==null || point.time<=it.endedAt) }
        val stats=PointSummaryBuilder();val endpoints=RouteEndpointBuilder()
        forEachPoint(range) { if(unassigned(it)) {stats.add(it);endpoints.add(it)} }
        val summary=stats.build()
        require(summary.count>0) { "This inferred outing no longer exists" }
        val all=summary.count==summary.outlierCount
        val sampler=UniformPointSampler(if(all) summary.count else summary.count-summary.outlierCount,displayLimit)
        forEachPoint(range) { if(unassigned(it) && (all || !it.outlier)) sampler.add(it) }
        OutingDetail(RecordingSession(header.id,header.startedAt,header.endedAt),sampler.points(),summary,dao.rangeVisits(range.from,range.until).map { it.value },inferred=true,endpoints=endpoints.build())
    }

    suspend fun deleteInferred(header: InferredOutingHeader) = lock.withLock {
        database.writeTransaction {
            val range=HistoryRange(header.startedAt,header.endedAt.plusMillis(1))
            val sessions=dao.rangeSessions(range.from,range.until).map { it.value }
            val ids=ArrayList<String>(500)
            forEachPoint(range) { point ->
                if(sessions.none { point.time>=it.startedAt && (it.endedAt==null || point.time<=it.endedAt) }) ids.add(point.id)
                if(ids.size==500) { dao.deletePointsById(ids.toList()); ids.clear() }
            }
            if(ids.isNotEmpty()) dao.deletePointsById(ids)
        }
    }
    private suspend fun closeSessions(at: Instant) { dao.openSessions().forEach { dao.put(SessionRow(it.value.copy(endedAt = maxOf(at, it.value.startedAt)))) } }
    private suspend fun closeVisits(at: Instant, inferred: Boolean) { dao.openVisits().forEach { dao.put(VisitRow(it.value.copy(departedAt = maxOf(at, it.value.arrivedAt), departureInferred = inferred))) } }
}

data class CountryDayReport(val days: List<CountryPresence>, val stays: List<CountryStay>, val unresolvedPoints: Long, val unresolvedVisits: Long)

data class DayTotals(val distances:Map<java.time.LocalDate,Double>,val counts:Map<java.time.LocalDate,Long>)
