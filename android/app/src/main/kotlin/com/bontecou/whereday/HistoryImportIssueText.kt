package com.bontecou.whereday

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.bontecou.whereday.export.HistoryImportIssue
import com.bontecou.whereday.export.HistoryImportProblem
import com.bontecou.whereday.export.HistoryImportRecordKind

/** Resources for a rejected import record, so a reason reads "Visit 1: departure is before arrival" in the UI language. */
internal fun historyImportRecordResource(record:HistoryImportRecordKind)=when(record) {
    HistoryImportRecordKind.visit->R.string.history_import_record_visit
    HistoryImportRecordKind.point->R.string.history_import_record_point
    HistoryImportRecordKind.row->R.string.history_import_record_row
    HistoryImportRecordKind.line->R.string.history_import_record_line
    HistoryImportRecordKind.googleRecord->R.string.history_import_record_google
}

internal fun historyImportProblemResource(problem:HistoryImportProblem)=when(problem) {
    HistoryImportProblem.departureBeforeArrival->R.string.history_import_problem_departure
    HistoryImportProblem.latitude->R.string.history_import_problem_latitude
    HistoryImportProblem.longitude->R.string.history_import_problem_longitude
    HistoryImportProblem.missingCoordinate->R.string.history_import_problem_missing_coordinate
    HistoryImportProblem.malformedCoordinates->R.string.history_import_problem_coordinates
    HistoryImportProblem.missingTime->R.string.history_import_problem_missing_time
    HistoryImportProblem.invalidTime->R.string.history_import_problem_time
    HistoryImportProblem.invalidNumber->R.string.history_import_problem_number
    HistoryImportProblem.invalidIdentifier->R.string.history_import_problem_identifier
    HistoryImportProblem.duplicateField->R.string.history_import_problem_duplicate_field
    HistoryImportProblem.invalidField->R.string.history_import_problem_field
    HistoryImportProblem.notRecord->R.string.history_import_problem_not_record
    HistoryImportProblem.columnCount->R.string.history_import_problem_column_count
    HistoryImportProblem.duplicateColumns->R.string.history_import_problem_duplicate_columns
    HistoryImportProblem.invalidGoogleRecord->R.string.history_import_problem_google
    HistoryImportProblem.other->R.string.history_import_problem_other
}

internal fun historyImportIssueText(resources:Resources,issue:HistoryImportIssue):String=resources.getString(R.string.history_import_issue,
    resources.getString(historyImportRecordResource(issue.record),issue.number),resources.getString(historyImportProblemResource(issue.problem)))

@Composable internal fun historyImportIssueText(issue:HistoryImportIssue):String=historyImportIssueText(LocalContext.current.resources,issue)
