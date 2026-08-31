package com.manzl.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentSemanticComponentDecoderTest {

    @Test
    fun `door blob becomes one confident component`() {
        val side = 32
        val classes = ByteArray(side * side)
        val confidence = FloatArray(side * side)
        for (y in 13..17) {
            for (x in 9..20) {
                val index = y * side + x
                classes[index] = StudentSemanticComponentDecoder.DOOR_CLASS_ID.toByte()
                confidence[index] = 0.91f
            }
        }

        val result = StudentSemanticComponentDecoder.decode(
            classIds = classes,
            classConfidence = confidence,
            side = side,
            targetClassIds = setOf(StudentSemanticComponentDecoder.DOOR_CLASS_ID),
        )

        assertEquals(1, result.size)
        assertEquals(StudentSemanticComponentDecoder.DOOR_CLASS_ID, result.single().classId)
        assertTrue(result.single().confidence > 0.90f)
        assertTrue(result.single().majorSpanPx >= 11f)
    }

    @Test
    fun `tiny false positive is rejected`() {
        val side = 32
        val classes = ByteArray(side * side)
        val confidence = FloatArray(side * side)
        val index = 16 * side + 16
        classes[index] = StudentSemanticComponentDecoder.WINDOW_CLASS_ID.toByte()
        confidence[index] = 0.99f

        val result = StudentSemanticComponentDecoder.decode(
            classIds = classes,
            classConfidence = confidence,
            side = side,
            targetClassIds = setOf(StudentSemanticComponentDecoder.WINDOW_CLASS_ID),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `diagonal stair blob preserves free orientation`() {
        val side = 48
        val classes = ByteArray(side * side)
        val confidence = FloatArray(side * side)
        for (i in 10..34) {
            for (offset in -1..1) {
                val x = i
                val y = i + offset
                val index = y * side + x
                classes[index] = StudentSemanticComponentDecoder.STAIR_CLASS_ID.toByte()
                confidence[index] = 0.88f
            }
        }

        val result = StudentSemanticComponentDecoder.decode(
            classIds = classes,
            classConfidence = confidence,
            side = side,
            targetClassIds = setOf(StudentSemanticComponentDecoder.STAIR_CLASS_ID),
        )

        assertEquals(1, result.size)
        val angle = result.single().rotationDegrees
        assertTrue(angle in 35f..55f)
    }
}
