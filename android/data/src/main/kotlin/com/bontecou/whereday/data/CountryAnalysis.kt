package com.bontecou.whereday.data

import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.time.*
import java.util.UUID

enum class CountryAnalysisStatus { preparing,waiting,lookingUp,nothingDue,finished,disabled,stopped,stopping,unavailable }
data class CountryAnalysisProgress(val completed:Long=0,val resolved:Long=0,val status:CountryAnalysisStatus?=null) {
    // Legacy diagnostic descriptions; visible UI resolves the typed status in its current locale.
    val message:String? get()=when(status) {
        null->null
        CountryAnalysisStatus.preparing->"Loading geographic time zones…"
        CountryAnalysisStatus.waiting->"Waiting before the next lookup. You can stop and continue later."
        CountryAnalysisStatus.lookingUp->"Looking up a saved location…"
        CountryAnalysisStatus.nothingDue->"No saved locations need analysis right now. Unavailable results retry after an hour."
        CountryAnalysisStatus.finished->"Identified $resolved of $completed locations. Unavailable results retry after an hour."
        CountryAnalysisStatus.disabled->"Enable network geocoding in Settings, or add a manual stay."
        CountryAnalysisStatus.stopped->"Analysis stopped. Completed results are saved; analyze again to continue."
        CountryAnalysisStatus.stopping->"Stopping analysis…"
        CountryAnalysisStatus.unavailable->"Country lookup is temporarily unavailable. Completed results are saved. Check your connection and try again later."
    }
}

/** A run never holds a database transaction open across network or rate-limit waits. */
class CountryAnalysis(
    private val database: HistoryDatabase,
    private val lookup: CountryLookup,
    private val version: String,
    private val clock: Clock = Clock.systemUTC(),
    private val minimumInterval: Duration = Duration.ofMinutes(1),
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private val dao = database.history()

    suspend fun run(allowed: suspend () -> Boolean, progress: (CountryAnalysisProgress) -> Unit): CountryAnalysisProgress {
        check(mutex.tryLock()) { "Country analysis is already running" }
        val token = UUID.randomUUID().toString()
        var state = CountryAnalysisProgress()
        try {
            ensureAllowed(allowed)
            database.writeTransaction {
                val previous = dao.countryAnalysis()
                dao.put(CountryAnalysisRow(runId=token,nextLookupAt=previous?.nextLookupAt ?: Instant.EPOCH))
            }
            var prepared=false
            suspend fun resolve(coordinate: Coordinate): CountryLocation? {
                ensureCurrent(token, allowed)
                dao.countryLookup(coordinate, version)?.let {
                    return CountryLocation(it.countryCode,it.timeZone)
                }
                if(!prepared) {
                    progress(state.copy(status=CountryAnalysisStatus.preparing))
                    lookup.prepare()
                    prepared=true
                }
                while (true) {
                    ensureCurrent(token, allowed)
                    val remaining = database.writeTransaction {
                        val row = dao.countryAnalysis()
                        check(row?.runId == token) { "Analysis was reset" }
                        val remaining = Duration.between(clock.instant(),row.nextLookupAt).toMillis()
                        if (remaining <= 0) dao.put(row.copy(nextLookupAt=clock.instant().plus(minimumInterval)))
                        remaining
                    }
                    if (remaining <= 0) break
                    progress(state.copy(status=CountryAnalysisStatus.waiting))
                    wait(minOf(remaining,1000))
                }
                ensureCurrent(token, allowed)
                progress(state.copy(status=CountryAnalysisStatus.lookingUp))
                return lookup.lookup(coordinate)
            }
            suspend fun apply(coordinate: Coordinate, result: CountryLocation?, update: suspend (Instant) -> Boolean) {
                ensureCurrent(token, allowed)
                val applied = database.writeTransaction {
                    if (dao.countryAnalysis()?.runId != token) return@writeTransaction false
                    if (!update(clock.instant())) return@writeTransaction false
                    if (result != null) dao.put(CountryLookupRow(coordinate,result.countryCode,result.timeZone,version))
                    true
                }
                if (applied) {
                    state=state.copy(completed=state.completed+1,resolved=state.resolved+if(result!=null) 1 else 0)
                    progress(state)
                }
            }
            while (true) {
                ensureCurrent(token,allowed)
                val now=clock.instant()
                val visits=dao.pendingCountryVisits(now,now.minusSeconds(3600))
                if(visits.isEmpty()) break
                for(row in visits) {
                    val source=row.value
                    val result=resolve(source.coordinate)
                    apply(source.coordinate,result) { attempted ->
                        val current=dao.visit(source.id)?.value ?: return@apply false
                        if(current.coordinate!=source.coordinate || (current.countryCode!=null && current.timeZone!=null)) return@apply false
                        dao.put(VisitRow(current.copy(countryCode=result?.countryCode ?: current.countryCode,timeZone=result?.timeZone ?: current.timeZone,countryResolutionAttemptedAt=attempted)))
                        true
                    }
                }
            }
            while (true) {
                ensureCurrent(token,allowed)
                val now=clock.instant()
                val points=dao.pendingCountryPoints(now,now.minusSeconds(3600))
                if(points.isEmpty()) break
                for(row in points) {
                    val source=row.value
                    val result=resolve(source.coordinate)
                    apply(source.coordinate,result) { attempted ->
                        val current=dao.point(source.id) ?: return@apply false
                        if(current.value.coordinate!=source.coordinate || current.value.outlier || (current.value.countryCode!=null && current.value.timeZone!=null)) return@apply false
                        dao.put(current.copy(value=current.value.copy(countryCode=result?.countryCode ?: current.value.countryCode,timeZone=result?.timeZone ?: current.value.timeZone,countryResolutionAttemptedAt=attempted)))
                        true
                    }
                }
            }
            return state.copy(status=if(state.completed==0L) CountryAnalysisStatus.nothingDue else CountryAnalysisStatus.finished).also(progress)
        } finally {
            try { withContext(NonCancellable) { lookup.finish() } }
            finally { mutex.unlock() }
        }
    }
    private suspend fun ensureCurrent(token: String, allowed: suspend () -> Boolean) {
        ensureAllowed(allowed)
        if(dao.countryAnalysis()?.runId!=token) throw CancellationException("Analysis was reset")
    }
    private suspend fun ensureAllowed(allowed: suspend () -> Boolean) {
        currentCoroutineContext().ensureActive()
        if(!allowed()) throw CancellationException("Network geocoding is disabled")
    }
}
