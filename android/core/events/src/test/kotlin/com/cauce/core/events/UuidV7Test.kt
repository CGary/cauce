package com.cauce.core.events

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UuidV7Test {
    private val pattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    @Test
    fun `ids have version 7 and the RFC 4122 variant`() {
        repeat(100) {
            val id = UuidV7.newId()
            assertTrue(pattern.matches(id), "not a UUID v7: $id")
            assertEquals(36, id.length)
        }
    }

    @Test
    fun `ids are unique`() {
        val ids = List(1_000) { UuidV7.newId() }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `ids sort by timestamp`() {
        val earlier = UuidV7.newId(nowMillis = 1_700_000_000_000)
        val later = UuidV7.newId(nowMillis = 1_700_000_001_000)
        assertTrue(earlier < later, "$earlier should sort before $later")
    }
}
