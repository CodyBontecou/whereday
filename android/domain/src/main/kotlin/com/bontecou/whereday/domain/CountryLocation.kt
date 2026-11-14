package com.bontecou.whereday.domain

import java.time.ZoneId

data class CountryLocation(val countryCode: String, val timeZone: String) {
    init { require(countryCode in CountryRegions.all); require(timeZone in ZoneId.getAvailableZoneIds()) }
}

interface CountryLookup {
    suspend fun prepare() {}
    suspend fun lookup(coordinate: Coordinate): CountryLocation?
    suspend fun finish() {}
}
