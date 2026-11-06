package com.bontecou.whereday

/** Test-only unapproved intermediary, simulating a forwarded result-token launch. */
class ForwardAutomationProbeActivity:android.app.Activity() {
    override fun onCreate(state:android.os.Bundle?) {
        super.onCreate(state)
        startActivity(android.content.Intent(AutomationActivity.ACTION_RUN).setClass(this,AutomationActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_FORWARD_RESULT).putExtra(AutomationActivity.EXTRA_REQUEST,intent.getStringExtra(AutomationActivity.EXTRA_REQUEST)))
        finish()
    }
}
