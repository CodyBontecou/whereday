package com.bontecou.whereday.platform

/** Persist only stable identities and sanitized diagnostics; never retain provider exception text. */
enum class AutomaticExportFailure(val diagnostic:String) {
    execution("Automatic export could not finish. Check purchase access, folder permissions and available storage, then retry."),
    interrupted("Automatic export was interrupted. Resume or cancel its saved request."),
    settingsUnavailable("Automatic export settings could not be loaded. Retry or reset them to continue."),
    purchase("Verified lifetime export access is required."),
    pendingChange("Finish or cancel the pending export before changing its schedule or format.")
}
enum class AutomaticExportNotice(val diagnostic:String) {
    missingDay("The day's file was missing, so the complete captured day was rebuilt."),
    jsonRewritten("The existing JSON could not be merged; the complete captured day was rewritten."),
    canceled("Pending export canceled. Documents and backups already written remain in their folder."),
    previousStatus("A previous automatic export status is unavailable. Review the current settings and pending request.")
}
enum class AutomaticExportActionNotice(val diagnostic:String) {
    settingsSaved("Automatic export settings saved."),
    verified("Export saved and verified."),
    canceled("Pending export canceled. External files remain in their folder."),
    reset("Automatic export settings reset.")
}
class AutomaticExportPendingChange:IllegalStateException(AutomaticExportFailure.pendingChange.diagnostic)
