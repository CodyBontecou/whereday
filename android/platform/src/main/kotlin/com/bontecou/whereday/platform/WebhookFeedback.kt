package com.bontecou.whereday.platform

/** Stable identities only; never retain endpoint, credentials or exception text in feedback. */
enum class WebhookFailure(val diagnostic:String) {
    preferences("Webhook preferences could not be loaded. Try reopening the app."),
    backgroundPaused("Background webhook delivery is paused. Check lifetime access and settings, then retry."),
    changed("Webhook settings changed. Reload them before saving."),
    unavailable("Webhook settings could not be opened. Reset them before configuring delivery again."),
    fields("Check the webhook URL and authentication fields."),
    work("Webhook work could not finish. Check lifetime access and settings, then retry.")
}
enum class WebhookNotice(val diagnostic:String) {
    saved("Webhook settings saved."),
    disabled("Webhooks disabled. Queued webhook data was removed."),
    reset("Webhook settings and queued data reset."),
    history("History queued for delivery."),
    flushed("Queued points are ready for delivery."),
    retry("Queued delivery will retry."),
    discarded("Queued delivery discarded. Journal history is unchanged."),
    connected("Connected"),
    connectionFailed("Connection failed. Check the endpoint and network."),
    connectionRejected("Connection failed")
}
data class WebhookFeedback(val notice:WebhookNotice,val httpStatus:Int?=null) {
    init {require(if(notice in setOf(WebhookNotice.connected,WebhookNotice.connectionRejected)) httpStatus in 1..999 else httpStatus==null)}
    val diagnostic:String get()=when(notice) {
        WebhookNotice.connected,WebhookNotice.connectionRejected->"${notice.diagnostic} (HTTP $httpStatus)."
        else->notice.diagnostic
    }
}
