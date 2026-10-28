package com.bontecou.whereday.domain

import java.time.ZoneId
import java.util.Locale

data class CountryLocation(val countryCode: String, val timeZone: String) {
    init { require(countryCode in countries); require(timeZone in ZoneId.getAvailableZoneIds()) }
    companion object { private val countries = Locale.getISOCountries().toSet() }
}

interface CountryLookup {
    suspend fun prepare() {}
    suspend fun lookup(coordinate: Coordinate): CountryLocation?
    suspend fun finish() {}
}
