package com.safelog.app.vehicle

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Area arguments as Vehicle SDK 2.3.2 declares them in its official API reference (document.pleos.ai):
 * `SeatArea(vararg values: SeatValue)`, `WindowArea(vararg values: WindowValue)`, and getters such as
 * `getCurrentWiperState(area: WindowArea, onComplete, onFailed)`. The classes below are stand-ins with those
 * shapes, not SDK classes. Before the fix only a single-enum constructor was understood, so every area was null.
 */
class ReflectAreaTest {
    enum class SeatValue { ROW_1_LEFT, ROW_1_CENTER, ROW_1_RIGHT, ROW_2_LEFT }
    enum class WindowValue { FRONT_WINDSHIELD, REAR_WINDSHIELD, ROW_1_LEFT, ROW_1_RIGHT }
    class SeatArea(vararg val values: SeatValue)
    class WindowArea(vararg val values: WindowValue)
    class OneValueArea(val value: SeatValue)

    class Seat {
        fun getCurrentSeatOccupancy(area: SeatArea, onComplete: (Boolean?) -> Unit, onFailed: (Exception) -> Unit) =
            onComplete(area.values.single() == SeatValue.ROW_1_LEFT)
    }

    class Wiper {
        fun getCurrentWiperState(area: WindowArea, onComplete: (String?) -> Unit, onFailed: (Exception) -> Unit) =
            onComplete("${area.values.single()} ON")
    }

    @Test fun varargAreaHoldsTheNamedValue() {
        val a = Reflect.area(WindowArea::class.java, "FRONT_WINDSHIELD") as WindowArea
        assertArrayEquals(arrayOf(WindowValue.FRONT_WINDSHIELD), a.values)
        assertEquals(listOf("FRONT_WINDSHIELD"), Reflect.areaNames(a))
        val w = Reflect.area(WindowArea::class.java, "ROW_1_LEFT") as WindowArea
        assertArrayEquals("the driver's window, not the windshield", arrayOf(WindowValue.ROW_1_LEFT), w.values)
    }

    @Test fun singleValueAreaStillWorks() {
        val a = Reflect.area(OneValueArea::class.java, "ROW_1_RIGHT") as OneValueArea
        assertEquals(SeatValue.ROW_1_RIGHT, a.value)
    }

    @Test fun unknownNameGivesNoAreaInsteadOfAnotherOne() {
        assertNull(Reflect.area(SeatArea::class.java, "DRIVER"))
        assertNull(Reflect.area(WindowArea::class.java, "FRONT_LEFT"))
    }

    @Test fun noNameMeansTheDriverSeat() {
        val a = Reflect.area(SeatArea::class.java, null) as SeatArea
        assertArrayEquals(arrayOf(SeatValue.ROW_1_LEFT), a.values)
    }

    @Test fun areaValuesListsEverySeat() {
        assertEquals(listOf("ROW_1_LEFT", "ROW_1_CENTER", "ROW_1_RIGHT", "ROW_2_LEFT"), Reflect.areaValues(SeatArea::class.java))
        assertEquals(listOf("ROW_1_LEFT", "ROW_1_CENTER", "ROW_1_RIGHT", "ROW_2_LEFT"), Reflect.areaValues(OneValueArea::class.java))
    }

    @Test fun getFillsTheAreaAndDeliversTheValue() {
        var wiper: Any? = null
        assertTrue(Reflect.get(Wiper(), "getCurrentWiperState", "FRONT_WINDSHIELD") { wiper = it })
        assertEquals("FRONT_WINDSHIELD ON", wiper)
        var driver: Any? = null
        var passenger: Any? = null
        assertTrue(Reflect.get(Seat(), "getCurrentSeatOccupancy", "ROW_1_LEFT") { driver = it })
        assertTrue(Reflect.get(Seat(), "getCurrentSeatOccupancy", "ROW_1_RIGHT") { passenger = it })
        assertEquals(true, driver)
        assertEquals(false, passenger)
    }
}
