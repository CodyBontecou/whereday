package com.bontecou.whereday.export

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/** Date-range choices from the iOS recipe sentence. Android stores concrete dates; a recipe keeps the preset. */
enum class ExportRangePreset { allTime, today, yesterday, last7Days, last30Days, thisMonth, month, year, custom }

/** A preset plus, for one month or one year, the day inside it that names the period. */
data class ExportRange(val preset: ExportRangePreset, val anchor: LocalDate? = null) {
    init { require((preset == ExportRangePreset.month || preset == ExportRangePreset.year) == (anchor != null)) }

    /** The inclusive days this range covers on [today], or null for all time and custom. */
    fun days(today: LocalDate): Pair<LocalDate, LocalDate>? = when(preset) {
        ExportRangePreset.allTime, ExportRangePreset.custom -> null
        ExportRangePreset.today -> today to today
        ExportRangePreset.yesterday -> today.minusDays(1).let { it to it }
        ExportRangePreset.last7Days -> today.minusDays(6) to today
        ExportRangePreset.last30Days -> today.minusDays(29) to today
        ExportRangePreset.thisMonth -> YearMonth.from(today).let { it.atDay(1) to it.atEndOfMonth() }
        ExportRangePreset.month -> YearMonth.from(anchor!!).let { it.atDay(1) to it.atEndOfMonth() }
        ExportRangePreset.year -> anchor!!.withDayOfYear(1).let { it to it.plusYears(1).minusDays(1) }
    }

    /** [options] with this range's dates; custom ranges keep the dates they already have. */
    fun applyTo(options: ExportOptions, today: LocalDate): ExportOptions = when(preset) {
        ExportRangePreset.custom -> options
        ExportRangePreset.allTime -> options.copy(from = ExportOptions.allDatesStart, until = Instant.MAX)
        else -> days(today)!!.let { (first, last) -> options.copy(from = first.atStartOfDay(options.zone).toInstant(), until = last.plusDays(1).atStartOfDay(options.zone).toInstant().minusNanos(1)) }
    }

    companion object {
        /** Reads stored dates back as the preset they match on [today], most specific first. */
        fun detect(options: ExportOptions, today: LocalDate): ExportRange {
            if(options.from == ExportOptions.allDatesStart && options.until == Instant.MAX) return ExportRange(ExportRangePreset.allTime)
            if(options.from == ExportOptions.allDatesStart || options.until == Instant.MAX) return ExportRange(ExportRangePreset.custom)
            val zone = options.zone
            val first = options.from.atZone(zone).toLocalDate()
            // Whole days only: a range starting mid-day or ending before the last instant is custom.
            if(first.atStartOfDay(zone).toInstant() != options.from) return ExportRange(ExportRangePreset.custom)
            val last = options.until.plusNanos(1).atZone(zone).toLocalDate().minusDays(1)
            if(last.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1) != options.until) return ExportRange(ExportRangePreset.custom)
            val candidates = listOf(ExportRange(ExportRangePreset.today), ExportRange(ExportRangePreset.yesterday), ExportRange(ExportRangePreset.last7Days),
                ExportRange(ExportRangePreset.last30Days), ExportRange(ExportRangePreset.thisMonth), ExportRange(ExportRangePreset.month, first), ExportRange(ExportRangePreset.year, first))
            return candidates.firstOrNull { it.days(today) == (first to last) }?.let { if(it.anchor != null) it.copy(anchor = if(it.preset == ExportRangePreset.month) first.withDayOfMonth(1) else first.withDayOfYear(1)) else it }
                ?: ExportRange(ExportRangePreset.custom)
        }
    }
}

/** Where a recipe's files go on Android: the share sheet or the chosen export folder. */
enum class ExportDestination { share, folder }

/** A saved Archive recipe: every export choice, its date preset and destination. */
data class ExportRecipe(val id: String = UUID.randomUUID().toString(), val name: String, val selection: ExportSelection, val range: ExportRange, val destination: ExportDestination = ExportDestination.share) {
    init { require(name.isNotBlank() && name.length <= 200) }

    /** The selection this recipe exports on [today]: its preset resolved to dates. */
    fun resolved(today: LocalDate): ExportSelection = selection.copy(options = range.applyTo(selection.options, today))

    /** True when the screen's current choices are exactly this recipe's on [today]. */
    fun matches(current: ExportSelection, destination: ExportDestination, today: LocalDate) = this.destination == destination && resolved(today) == current

    companion object {
        /** First visit: two examples show what a recipe can be; they delete like any other. */
        fun examples(daily: String, weekly: String, zone: ZoneId) = listOf(
            ExportRecipe(name = daily, selection = ExportSelection(format = ExportFormat.markdown, options = ExportOptions(kind = DataKind.all, zone = zone), filename = "Whereday/Daily Track - {date}", splitDays = true),
                range = ExportRange(ExportRangePreset.thisMonth)),
            ExportRecipe(name = weekly, selection = ExportSelection(format = ExportFormat.gpx, options = ExportOptions(kind = DataKind.points, zone = zone), filename = FilenamePlanner.readablePattern),
                range = ExportRange(ExportRangePreset.last7Days)),
        )
    }
}

/** Recipes persist as one JSON array; an unreadable entry is dropped rather than failing the whole list. */
object ExportRecipeCodec {
    const val MAXIMUM_RECIPES = 50

    fun encode(recipes: List<ExportRecipe>): String = JsonArray().apply {
        recipes.take(MAXIMUM_RECIPES).forEach { recipe -> add(JsonObject().apply {
            addProperty("version", 1); addProperty("id", recipe.id); addProperty("name", recipe.name)
            addProperty("selection", ExportSelectionCodec.encode(recipe.selection))
            addProperty("preset", recipe.range.preset.name); recipe.range.anchor?.let { addProperty("anchor", it.toString()) }
            addProperty("destination", recipe.destination.name)
        }) }
    }.toString()

    fun decode(saved: String?): List<ExportRecipe> {
        if(saved.isNullOrEmpty()) return emptyList()
        val array = runCatching { JsonParser.parseString(saved).asJsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element -> runCatching {
            val json = element.asJsonObject
            require(json["version"].asInt == 1)
            val preset = ExportRangePreset.valueOf(json["preset"].asString)
            ExportRecipe(json["id"].asString, json["name"].asString, ExportSelectionCodec.decode(json["selection"].asString),
                ExportRange(preset, json["anchor"]?.asString?.let(LocalDate::parse)), ExportDestination.valueOf(json["destination"].asString))
        }.getOrNull() }.distinctBy { it.id }.take(MAXIMUM_RECIPES)
    }
}
