package com.bontecou.whereday

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.bontecou.whereday.export.WebhookValidationFailure
import com.bontecou.whereday.platform.*
import java.text.NumberFormat

@Composable internal fun webhookValidationText(failure:WebhookDraftFailure):String=stringResource(when(failure) {
    WebhookDraftFailure.https->R.string.webhook_validation_https
    WebhookDraftFailure.fields->R.string.webhook_validation_fields
    is WebhookDraftFailure.protocol->when(failure.reason) {
        WebhookValidationFailure.url->R.string.webhook_validation_url
        WebhookValidationFailure.https->R.string.webhook_validation_protocol_https
        WebhookValidationFailure.endpoint->R.string.webhook_validation_endpoint
        WebhookValidationFailure.authenticationValue->R.string.webhook_validation_authentication_value
        WebhookValidationFailure.apiKey->R.string.webhook_validation_api_key
        WebhookValidationFailure.basicValue->R.string.webhook_validation_basic_value
        WebhookValidationFailure.basicRequired->R.string.webhook_validation_basic_required
        WebhookValidationFailure.headerName->R.string.webhook_validation_header_name
        WebhookValidationFailure.urlLength->R.string.webhook_validation_url_length
    }
})
@Composable internal fun webhookFailureText(failure:WebhookFailure):String=stringResource(when(failure) {
    WebhookFailure.preferences->R.string.webhook_failure_preferences
    WebhookFailure.backgroundPaused->R.string.webhook_failure_background_plan
    WebhookFailure.changed->R.string.webhook_failure_changed
    WebhookFailure.unavailable->R.string.webhook_failure_unavailable
    WebhookFailure.fields->R.string.webhook_failure_fields
    WebhookFailure.work->R.string.webhook_failure_work_plan
})
@Composable internal fun webhookFeedbackText(feedback:WebhookFeedback):String {
    val resource=when(feedback.notice) {
        WebhookNotice.saved->R.string.webhook_notice_saved
        WebhookNotice.disabled->R.string.webhook_notice_disabled
        WebhookNotice.reset->R.string.webhook_notice_reset
        WebhookNotice.history->R.string.webhook_notice_history
        WebhookNotice.flushed->R.string.webhook_notice_flushed
        WebhookNotice.retry->R.string.webhook_notice_retry
        WebhookNotice.discarded->R.string.webhook_notice_discarded
        WebhookNotice.connected->R.string.webhook_notice_connected
        WebhookNotice.connectionFailed->R.string.webhook_notice_connection_failed
        WebhookNotice.connectionRejected->R.string.webhook_notice_connection_rejected
    }
    val status=feedback.httpStatus
    return if(status!=null) stringResource(resource,status) else stringResource(resource)
}
@Composable private fun webhookCount(resource:Int,count:Long):String=pluralStringResource(resource,count.coerceIn(0,Int.MAX_VALUE.toLong()).toInt(),NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0]).format(count))
@Composable internal fun webhookPointCount(count:Long,waiting:Boolean=false)=webhookCount(if(waiting) R.plurals.webhook_waiting_points else R.plurals.webhook_points,count)
@Composable internal fun webhookRequestCount(count:Long)=webhookCount(R.plurals.webhook_pending_requests,count)
@Composable internal fun webhookVisitCount(count:Long)=webhookCount(R.plurals.webhook_visits,count)
