package com.bontecou.whereday

import com.bontecou.whereday.billing.*
import org.junit.Assert.*
import org.junit.Test

class SubscriptionProductionPresentationTest {
    @Test fun productionAlwaysUsesUnmodifiedLiveInputsAndHasNoFixtureWriter() {
        val live=BillingState(AccessState.verificationRequired,notice=BillingNotice.verificationFailed)
        val history=PlanHistory(true,StorePlan.yearly,"live-hash")
        val p=subscriptionPresentation(live,history)
        assertSame(live,p.billing);assertSame(history,p.history);assertTrue(p.storeActionsAllowed)
        try {Class.forName("com.bontecou.whereday.SubscriptionPreview");fail("QA writer leaked into release")}
        catch(_:ClassNotFoundException) {}
        try {Class.forName("com.bontecou.whereday.QaSubscriptionPreviewProvider");fail("QA provider leaked into release")}
        catch(_:ClassNotFoundException) {}
    }
}
