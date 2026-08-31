package com.manzl.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomLabelSemanticsTest {

    @Test
    fun `recognizes common Saudi residential CAD room labels`() {
        assertEquals("مجلس", RoomLabelSemantics.match("MAJLIS")?.labelArabic)
        assertEquals("مطبخ", RoomLabelSemantics.match("Kitchen 4.20 x 3.60")?.labelArabic)
        assertEquals("غرفة نوم رئيسية", RoomLabelSemantics.match("MASTER BEDROOM")?.labelArabic)
        assertEquals("حمام", RoomLabelSemantics.match("WC")?.labelArabic)
        assertEquals("غرفة خادمة", RoomLabelSemantics.match("MAID ROOM")?.labelArabic)
    }

    @Test
    fun `keeps unknown drawing notes out of room semantics`() {
        assertNull(RoomLabelSemantics.match("A-103"))
        assertNull(RoomLabelSemantics.match("SECTION DETAIL"))
        assertNull(RoomLabelSemantics.match("2500"))
    }

    @Test
    fun `semantic confidence stays below geometry authority ceiling`() {
        val match = RoomLabelSemantics.match("KITCHEN")
        assertTrue(match != null)
        assertTrue(match!!.confidence in 0.5f..0.99f)
    }
}
