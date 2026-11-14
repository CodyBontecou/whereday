package com.bontecou.whereday

import com.bontecou.whereday.domain.Coordinate
import com.bontecou.whereday.domain.SavedPlace
import com.bontecou.whereday.domain.SavedPlaceKind
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
    @Test fun kindAndNoteRoundTripAndVersionOneRowsRestoreWithout() {
        for(value in listOf(place.copy(kind=SavedPlaceKind.home,notes="Blue door"),place.copy(kind=SavedPlaceKind.gym,notes=""),place.copy(notes=null))) {
            assertEquals(value,SavedPlaceSnapshot.restored(SavedPlaceSnapshot.saved(value)))
        }
        assertEquals(place,SavedPlaceSnapshot.restored(listOf(1,place.id,place.name,place.coordinate.latitude,place.coordinate.longitude,true,"Stored address",place.radius)))
    }
    @Test fun malformedAndInvalidRowsCannotRestoreAnEditorBaseline() {
        val saved=SavedPlaceSnapshot.saved(place)
        assertNull(SavedPlaceSnapshot.restored(emptyList()))
        for((index,value) in listOf(0 to 3,2 to "",8 to "mansion",9 to "true",3 to 91.0,4 to Double.NaN,5 to "true",7 to 24.0,7 to Double.POSITIVE_INFINITY)) {
            assertNull(SavedPlaceSnapshot.restored(saved.toMutableList().apply {this[index]=value}))
        }
        assertNull(SavedPlaceSnapshot.restored(saved.dropLast(1)))
    }
}
