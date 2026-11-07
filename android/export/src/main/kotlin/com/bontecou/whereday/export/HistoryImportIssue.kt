package com.bontecou.whereday.export

import java.time.DateTimeException

/** Which part of the source file a rejected history record came from. */
enum class HistoryImportRecordKind {visit,point,row,line,googleRecord}

/** Stable reasons for a rejected history record. The app maps these to localized resources; [HistoryImportIssue.diagnostic] stays English. */
enum class HistoryImportProblem {
    departureBeforeArrival,latitude,longitude,missingCoordinate,malformedCoordinates,missingTime,invalidTime,invalidNumber,
    invalidIdentifier,duplicateField,invalidField,notRecord,columnCount,duplicateColumns,invalidGoogleRecord,other
}

/** One rejected record: where it was ([record] and its 1-based [number]) and why. [diagnostic] is the legacy English line also kept in [ImportScan.errors]. */
data class HistoryImportIssue(val record:HistoryImportRecordKind,val number:Int,val problem:HistoryImportProblem,val diagnostic:String)

/** A record-level failure with a typed reason, so the UI never shows a raw `require` message such as "Failed requirement.". */
class InvalidHistoryRecord(val problem:HistoryImportProblem,message:String):IllegalArgumentException(message)

/** Nothing in the file could be imported. [issue] is the first rejected record, if any. */
class NoImportableHistory(val issue:HistoryImportIssue?):IllegalArgumentException(issue?.diagnostic ?: "No importable history found")

internal fun historyImportProblem(failure:Throwable):HistoryImportProblem=when(failure) {
    is InvalidHistoryRecord->failure.problem
    is NumberFormatException->HistoryImportProblem.invalidNumber
    is DateTimeException->HistoryImportProblem.invalidTime
    else->HistoryImportProblem.other
}

internal fun historyImportLabel(record:HistoryImportRecordKind,number:Int)=when(record) {
    HistoryImportRecordKind.visit->"Visit $number"
    HistoryImportRecordKind.point->"Point $number"
    HistoryImportRecordKind.row->"Row $number"
    HistoryImportRecordKind.line->"Line $number"
    HistoryImportRecordKind.googleRecord->"Google record $number"
}
