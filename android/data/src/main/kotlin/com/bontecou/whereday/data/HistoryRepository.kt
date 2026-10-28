package com.bontecou.whereday.data

import com.bontecou.whereday.domain.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Duration
import java.time.Instant

class VisitChanged:IllegalStateException("This visit changed. Close the editor and review the latest visit before trying again.")

class HistoryRepository(val database: HistoryDatabase, private val clock: Clock = Clock.systemUTC()) {
    val dao = database.history()
    private val lock = Mutex()
    private val detector = VisitDetector()

    suspend fun start(stopAfterHours: Int): RecordingRow = lock.withLock {
        database.writeTransaction {
            val existing = dao.recording()
            if (existing?.requested == true && dao.openSessions().any { it.value.id == existing.sessionId }) return@writeTransaction existing
            val now = clock.instant()
            closeSessions(now)
            val session = RecordingSession(startedAt = now)
            dao.put(SessionRow(session))
            RecordingRow(requested = true, sessionId = session.id,
                deadline = stopAfterHours.takeIf { it > 0 }?.let { now.plus(Duration.ofHours(it.toLong())) }).also { dao.put(it) }
        }
    }

    suspend fun stop(reason: String? = null) = lock.withLock {
        database.writeTransaction {
            val now = clock.instant()
            closeSessions(now)
            closeVisits(now, inferred = true)
            dao.put(DwellRow())
            dao.put((dao.recording() ?: RecordingRow()).copy(requested = false, sessionId = null, deadline = null, interruption = reason))
        }
    }

    suspend fun ingest(points: List<TrackPoint>) = lock.withLock {
        database.writeTransaction {
            var recording = dao.recording() ?: return@writeTransaction
            if (!recording.requested) return@writeTransaction
            val session = dao.openSessions().firstOrNull { it.value.id == recording.sessionId }?.value ?: return@writeTransaction
            var state = dao.dwell()?.state ?: DwellState()
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
                    state=transition.state.copy(visitId=saved.id)
                }
                if(transition.arrived==null) state = transition.state
                recent = (recent + point).takeLast(2)
                recording = recording.copy(lastFix = point.time, interruption = null)
            }
            dao.put(DwellRow(state = state))
            dao.put(recording)
        }
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

    suspend fun saveVisit(value:Visit,expected:Visit?,reuse:SavedPlaceReuseSave?=null):Visit = lock.withLock {
        val visit=persistedVisit(value)
        val baseline=expected?.let(::persistedVisit)
        database.writeTransaction {
            require(expected==null || expected.id==visit.id) {"The visit identity changed."}
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
            require(visit.arrivedAt<=now && (visit.departedAt==null || visit.departedAt in visit.arrivedAt..now)) {"Enter valid past visit times."}
            if(expected==null) require(visit.source==VisitSource.manual && !visit.name.isNullOrBlank() && visit.confirmation==Confirmation.confirmed) {"New visits must be named manual visits."}
            dao.put(VisitRow(visit))
            // A manually closed detector visit must not be reopened/overwritten by the next dwell sample.
            if(visit.departedAt!=null && dao.dwell()?.state?.visitId==visit.id) dao.put(DwellRow())
            reusablePlan?.let {dao.put(PlaceRow(it.value))}
            currentCoroutineContext().ensureActive()
            visit
        }
    }

    suspend fun deleteVisit(expected:Visit) = lock.withLock {
        database.writeTransaction {
            if(dao.visit(expected.id)?.value!=persistedVisit(expected)) throw VisitChanged()
            dao.deleteVisit(expected.id)
            if(dao.dwell()?.state?.visitId==expected.id) dao.put(DwellRow())
        }
    }

    suspend fun markInterrupted(reason: String) = lock.withLock {
        database.writeTransaction {
            val current = dao.recording() ?: return@writeTransaction
            if (current.requested) {
                closeSessions(current.lastFix ?: clock.instant())
                closeVisits(current.lastFix ?: clock.instant(), true)
                dao.put(DwellRow())
                dao.put(current.copy(requested = false, sessionId = null, interruption = reason))
            }
        }
    }

    fun observeWindow(range: HistoryRange,inference: InferenceRules?=null,includeOutliers:Boolean=true): Flow<HistoryWindow> = database.invalidationTracker.createFlow("points","visits","sessions")
        .conflate().map { readWindow(range,inference=inference,includeOutliers=includeOutliers) }

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
                closeVisits(clock.instant(),true)
                dao.put(DwellRow())
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
