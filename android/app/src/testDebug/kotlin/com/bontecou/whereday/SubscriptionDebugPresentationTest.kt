package com.bontecou.whereday

import com.bontecou.whereday.billing.*
import org.junit.*
import org.junit.Assert.*

class SubscriptionDebugPresentationTest {
    @After fun clear() {SubscriptionPreview.override.value=null}
    @Test fun existingDebugPreviewStillAffectsOnlySettingsAndNeverChangesLiveInputs() {
        val live=BillingState(AccessState.free)
        val history=PlanHistory()
        val fixture=SubscriptionPreview.Value(BillingState(AccessState.active,activeProductId="synthetic"),PlanHistory(true))
        SubscriptionPreview.override.value=fixture
        val settings=subscriptionPresentation(live,history)
        assertSame(fixture.billing,settings.billing);assertSame(fixture.history,settings.history)
        assertTrue("Preserve existing debug callback behavior",settings.storeActionsAllowed)
        val export=subscriptionPresentation(live,history,SubscriptionPresentationOwner.export)
        assertSame(live,export.billing);assertSame(history,export.history)
        SubscriptionPreview.override.value=null
        assertSame(live,subscriptionPresentation(live,history).billing)
    }
}
