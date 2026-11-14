package com.bontecou.whereday

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import com.bontecou.whereday.export.*
import com.bontecou.whereday.platform.WebhookConfiguration

internal sealed interface WebhookDraftFailure {
    data object https:WebhookDraftFailure
    data class protocol(val reason:WebhookValidationFailure):WebhookDraftFailure
    data object fields:WebhookDraftFailure
}

/** Deliberately absent from SavedStateHandle/Bundle; secret drafts survive rotation only in memory. */
internal data class WebhookDraft(val settings:WebhookSettings=WebhookSettings(),val credentials:WebhookCredentials=WebhookCredentials()) {
    override fun toString()="WebhookDraft([redacted])"
    fun normalized()=copy(settings=settings.copy(endpoint=settings.endpoint.trim(),allowInsecureHttp=false))
    fun validate():WebhookDraftFailure? {
        if(settings.endpoint.isBlank() && !settings.enabled) return null
        if(!settings.endpoint.trim().startsWith("https://",ignoreCase=true)) return WebhookDraftFailure.https
        return try {WebhookProtocol.request(settings.copy(allowInsecureHttp=false),credentials,"settings-validation");null}
        catch(e:WebhookValidationException) {WebhookDraftFailure.protocol(e.reason)}
        catch(_:IllegalArgumentException) {WebhookDraftFailure.fields}
    }
    fun matches(config:WebhookConfiguration)=settings==config.settings && credentials==config.credentials
}

internal class WebhookEditorViewModel:ViewModel() {
    var baseline by mutableStateOf<WebhookConfiguration?>(null);private set
    var draft by mutableStateOf(WebhookDraft());private set
    val dirty get()=baseline?.let {!draft.matches(it)} ?: false
    fun edit(value:WebhookDraft) {draft=value}
    fun reload(config:WebhookConfiguration) {baseline=config;draft=WebhookDraft(config.settings,config.credentials)}
    fun reconcile(config:WebhookConfiguration) {if(baseline==null || !dirty || draft.matches(config)) reload(config)}
    fun resetDraft() {draft=WebhookDraft()}
}
