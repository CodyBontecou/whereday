package com.bontecou.whereday

import com.bontecou.whereday.domain.Coordinate
import com.bontecou.whereday.domain.SavedPlace
import org.junit.Assert.*
import org.junit.Test

class SavedPlaceSnapshotTest {
    private val place=SavedPlace("id","Original reviewed name",Coordinate(18.123456789,-66.123456789),"Stored address",150.125)
    @Test fun retainsEveryFieldOfTheOriginalConflictBaseline() {
        assertEquals(place,SavedPlaceSnapshot.restored(SavedPlaceSnapshot.saved(place)))
        assertNotEquals(place,place.copy(name="Independent update"))
    }
    @Test fun nullAndEmptyAddressesRemainDifferent() {
        for(address in listOf(null,"","An address")) {
            val value=place.copy(address=address)
            assertEquals(value,SavedPlaceSnapshot.restored(SavedPlaceSnapshot.saved(value)))
        }
    }
    @Test fun malformedAndInvalidRowsCannotRestoreAnEditorBaseline() {
        val saved=SavedPlaceSnapshot.saved(place)
        assertNull(SavedPlaceSnapshot.restored(emptyList()))
        for((index,value) in listOf(0 to 2,2 to "",3 to 91.0,4 to Double.NaN,5 to "true",7 to 24.0,7 to Double.POSITIVE_INFINITY)) {
            assertNull(SavedPlaceSnapshot.restored(saved.toMutableList().apply {this[index]=value}))
        }
        assertNull(SavedPlaceSnapshot.restored(saved.dropLast(1)))
    }
}
