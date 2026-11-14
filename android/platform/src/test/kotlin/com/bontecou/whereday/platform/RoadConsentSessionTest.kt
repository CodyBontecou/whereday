package com.bontecou.whereday.platform

import com.bontecou.whereday.data.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class RoadConsentSessionTest {
    private val provider=RoadProviderDisclosure("Synthetic routing","https://example.test/privacy")
    private class Fixture {
        var settings=AppSettings()
        val journal=MutableStateFlow(JournalClearState(generation="a"))
        var writes=0;var configured=true;var fail=false;var revoked=false
        var nonCooperative=false
        var gate:CompletableDeferred<Unit>?=null
        val entered=CompletableDeferred<Unit>()
        fun session(provider:RoadProviderDisclosure?)=RoadConsentSession(provider,{configured},journal,{transform->
            entered.complete(Unit);if(nonCooperative) withContext(NonCancellable) {gate?.await()} else gate?.await();if(fail) error("private storage error")
            settings=transform(settings).copy(roadAccessRevision="saved-${++writes}")
        },beforeDisable={revoked=true})
    }
    @Test fun missingProviderConfigurationNetworkOrForegroundCannotCreateAReview() {
        val f=Fixture();val session=f.session(provider)
        try {
            session.review(f.settings);assertNull(session.state.value.review)
            session.foreground();f.configured=false;session.review(f.settings);assertNull(session.state.value.review)
            f.configured=true;session.review(f.settings.copy(networkGeocoding=false));assertNull(session.state.value.review)
            f.journal.value=JournalClearState(JournalClearPhase.clearing);session.review(f.settings);assertNull(session.state.value.review)
        } finally {session.close()}
        val missing=f.session(null);try {missing.foreground();missing.review(f.settings);assertNull(missing.state.value.review)} finally {missing.close()}
    }
    @Test fun acceptanceIsSingleUseAndChangesOnlyRoadConsent()=runBlocking {
        val f=Fixture();val session=f.session(provider)
        try {
            session.foreground();session.review(f.settings);val id=requireNotNull(session.state.value.review).id
            assertFalse(f.settings.roadDisplayEnabled);requireNotNull(session.confirm(id)).join()
            assertNull(session.confirm(id));assertTrue(f.settings.roadDisplayEnabled);assertTrue(f.settings.networkGeocoding)
            assertTrue(session.state.value.saved);assertEquals(1,f.writes)
        } finally {session.close()}
    }
    @Test fun canceledOrBackgroundedReviewCannotBeResurrected()=runBlocking {
        val f=Fixture();val session=f.session(provider)
        try {
            session.foreground();session.review(f.settings);val first=requireNotNull(session.state.value.review).id
            session.dismiss(first);assertNull(session.confirm(first))
            session.review(f.settings);val old=requireNotNull(session.state.value.review).id
            session.background();session.foreground();assertNull(session.confirm(old));assertEquals(0,f.writes)
        } finally {session.close()}
    }
    @Test fun staleRevisionNetworkGenerationAndProviderAreRecheckedInsideQueuedEdit()=runBlocking {
        for(change in 0..4) {
            val f=Fixture();f.gate=CompletableDeferred();val session=f.session(provider)
            try {
                session.foreground();session.review(f.settings);val job=requireNotNull(session.confirm(requireNotNull(session.state.value.review).id))
                f.entered.await()
                when(change) {
                    0->f.settings=f.settings.copy(roadAccessRevision="changed")
                    1->f.settings=f.settings.copy(networkGeocoding=false)
                    2->f.journal.value=JournalClearState(generation="b")
                    3->f.configured=false
                    4->f.journal.value=JournalClearState(JournalClearPhase.clearing,generation="a")
                }
                f.gate!!.complete(Unit);job.join();assertFalse(f.settings.roadDisplayEnabled)
                assertEquals(0,f.writes);assertEquals(RoadConsentFailure.stale,session.state.value.failure)
            } finally {f.gate!!.complete(Unit);session.close()}
        }
    }
    @Test fun leavingScreenCancelsQueuedMutationEvenAfterReturning()=runBlocking {
        val f=Fixture();f.nonCooperative=true;f.gate=CompletableDeferred();val session=f.session(provider)
        try {
            session.foreground();session.review(f.settings);val job=requireNotNull(session.confirm(requireNotNull(session.state.value.review).id))
            f.entered.await();session.background();session.foreground();f.gate!!.complete(Unit);job.join()
            assertTrue(job.isCancelled);assertEquals(0,f.writes);assertFalse(f.settings.roadDisplayEnabled)
        } finally {f.gate!!.complete(Unit);session.close()}
    }
    @Test fun failureDoesNotEnableAndRequiresNewReviewBeforeRetry()=runBlocking {
        val f=Fixture();f.fail=true;val session=f.session(provider)
        try {
            session.foreground();session.review(f.settings);val id=requireNotNull(session.state.value.review).id
            requireNotNull(session.confirm(id)).join();assertEquals(RoadConsentFailure.storage,session.state.value.failure)
            assertFalse(f.settings.roadDisplayEnabled);assertNull(session.confirm(id))
            f.fail=false;session.review(f.settings);requireNotNull(session.confirm(requireNotNull(session.state.value.review).id)).join()
            assertTrue(f.settings.roadDisplayEnabled)
        } finally {session.close()}
    }
    @Test fun disableWorksWithoutConfiguredProviderAndPreservesNetworkPolicy()=runBlocking {
        val f=Fixture();f.settings=f.settings.copy(roadDisplayEnabled=true,networkGeocoding=false);f.configured=false
        val session=f.session(null)
        try {session.foreground();requireNotNull(session.disable()).join();assertFalse(f.settings.roadDisplayEnabled);assertFalse(f.settings.networkGeocoding)}
        finally {session.close()}
    }
    @Test fun providerDisclosureRejectsUnreviewableIdentityAndUnsafePrivacyLinks() {
        for(url in listOf("http://example.test/privacy","javascript:alert(1)","https://user:secret@example.test/privacy")) {
            try {RoadProviderDisclosure("Routing",url);fail("Unsafe disclosure accepted")} catch(_:IllegalArgumentException) {}
        }
    }
    @Test fun revocationFencesImmediatelyAndSurvivesBackgroundCloseAndClearGeneration()=runBlocking {
        val f=Fixture();f.settings=f.settings.copy(roadDisplayEnabled=true);f.gate=CompletableDeferred()
        val session=f.session(null)
        try {
            session.foreground();val job=requireNotNull(session.disable());assertTrue(f.revoked)
            f.entered.await();session.background();session.close();f.journal.value=JournalClearState(generation="new")
            f.gate!!.complete(Unit);job.join();assertFalse(f.settings.roadDisplayEnabled);assertEquals(1,f.writes)
        } finally {f.gate!!.complete(Unit);session.close()}
    }

}
