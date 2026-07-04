package com.vibe.app.feature.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileContentClampTest {

    @Test
    fun `small file passes through unchanged`() {
        val content = (1..10).joinToString("\n") { "line $it" }
        val result = clampFileContent(content)
        assertFalse(result.truncated)
        assertEquals(content, result.content)
        assertEquals(10, result.totalLines)
    }

    @Test
    fun `file over line limit is cut to maxLines`() {
        val content = (1..3000).joinToString("\n") { "line $it" }
        val result = clampFileContent(content, maxLines = 2000, maxChars = 50_000)
        assertTrue(result.truncated)
        assertEquals(3000, result.totalLines)
        assertEquals(2000, result.content.lines().size)
        assertTrue(result.content.endsWith("line 2000"))
    }

    @Test
    fun `file over char limit is cut to maxChars`() {
        val content = "x".repeat(60_000) // 单行超长
        val result = clampFileContent(content, maxLines = 2000, maxChars = 50_000)
        assertTrue(result.truncated)
        assertEquals(50_000, result.content.length)
    }
}
