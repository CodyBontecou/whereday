package com.bontecou.whereday

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.bontecou.whereday.domain.SavedPlaceImportSummary
import java.text.Normalizer
import java.util.Locale
import com.bontecou.whereday.export.*

internal data class PlaceUiText(val resource:Int,val args:List<Any> = emptyList()) {
    @Composable fun text()=stringResource(resource,*args.toTypedArray())
}
internal fun savedPlaceCsvText(issue:SavedPlaceCsvIssue):PlaceUiText = when(issue.problem) {
    SavedPlaceCsvProblem.fileSize->PlaceUiText(R.string.saved_csv_file_size)
    SavedPlaceCsvProblem.encoding->PlaceUiText(R.string.saved_csv_encoding)
    SavedPlaceCsvProblem.headerQuotes->PlaceUiText(R.string.saved_csv_header_quotes)
    SavedPlaceCsvProblem.headerEmpty->PlaceUiText(R.string.saved_csv_header_empty)
    SavedPlaceCsvProblem.headerDuplicate->PlaceUiText(R.string.saved_csv_header_duplicate)
    SavedPlaceCsvProblem.headerMissing->PlaceUiText(R.string.saved_csv_header_missing,listOf(issue.columns.joinToString(", ")))
    SavedPlaceCsvProblem.rowLimit->PlaceUiText(R.string.saved_csv_row_limit)
    SavedPlaceCsvProblem.fileEmpty->PlaceUiText(R.string.saved_csv_file_empty)
    SavedPlaceCsvProblem.noRows->PlaceUiText(R.string.saved_csv_no_rows)
    SavedPlaceCsvProblem.fieldCount->PlaceUiText(R.string.saved_csv_field_count,listOf(issue.expected,issue.actual))
    SavedPlaceCsvProblem.placeName->PlaceUiText(R.string.saved_csv_name)
    SavedPlaceCsvProblem.latitude->PlaceUiText(R.string.saved_csv_latitude)
    SavedPlaceCsvProblem.longitude->PlaceUiText(R.string.saved_csv_longitude)
    SavedPlaceCsvProblem.radius->PlaceUiText(R.string.saved_csv_radius)
    SavedPlaceCsvProblem.quoteWholeField->PlaceUiText(R.string.saved_csv_quote_whole)
    SavedPlaceCsvProblem.afterQuote->PlaceUiText(R.string.saved_csv_after_quote)
    SavedPlaceCsvProblem.closingQuote->PlaceUiText(R.string.saved_csv_closing_quote)
}

@Composable internal fun savedPlacePlanText(summary:SavedPlaceImportSummary)=stringResource(R.string.saved_csv_plan,
    pluralStringResource(R.plurals.saved_csv_added,summary.added,summary.added),
    pluralStringResource(R.plurals.saved_csv_updated,summary.updated,summary.updated),
    pluralStringResource(R.plurals.saved_csv_skipped,summary.skipped,summary.skipped))

private val savedPlaceCombiningMarks=Regex("\\p{M}+")
internal fun savedPlaceSearchKey(text:String,locale:Locale)=Normalizer.normalize(text.lowercase(locale),Normalizer.Form.NFD).replace(savedPlaceCombiningMarks,"")
