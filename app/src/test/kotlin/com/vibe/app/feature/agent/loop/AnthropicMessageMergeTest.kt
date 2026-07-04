package com.vibe.app.feature.agent.loop

import com.vibe.app.data.dto.anthropic.common.MessageRole
import com.vibe.app.data.dto.anthropic.common.TextContent
import com.vibe.app.data.dto.anthropic.request.InputMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class AnthropicMessageMergeTest {

    @Test
    fun `consecutive same-role messages are merged into one`() {
        val messages = listOf(
            InputMessage(role = MessageRole.ASSISTANT, content = listOf(TextContent("summary 1"))),
            InputMessage(role = MessageRole.ASSISTANT, content = listOf(TextContent("summary 2"))),
            InputMessage(role = MessageRole.USER, content = listOf(TextContent("hi"))),
            InputMessage(role = MessageRole.USER, content = listOf(TextContent("tool results"))),
        )
        val merged = mergeConsecutiveSameRole(messages)
        assertEquals(2, merged.size)
        assertEquals(2, merged[0].content.size)
        assertEquals(MessageRole.ASSISTANT, merged[0].role)
        assertEquals(MessageRole.USER, merged[1].role)
    }

    @Test
    fun `alternating roles are untouched`() {
        val messages = listOf(
            InputMessage(role = MessageRole.USER, content = listOf(TextContent("a"))),
            InputMessage(role = MessageRole.ASSISTANT, content = listOf(TextContent("b"))),
        )
        assertEquals(messages, mergeConsecutiveSameRole(messages))
    }
}
