package com.mindfulhome.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TodoLogicTest {

    @Test
    fun validateTodoUpsert_coversBranches() {
        assertEquals("Intent is required", validateTodoUpsert("  ", null, null, 1))
        assertEquals(
            "Duration is required when deadline is set",
            validateTodoUpsert("x", null, 1L, 1),
        )
        assertEquals(
            "Duration is required when deadline is set",
            validateTodoUpsert("x", 0, 1L, 1),
        )
        assertEquals("Priority must be 1..4", validateTodoUpsert("x", 5, null, 0))
        assertNull(validateTodoUpsert("x", 5, 1L, 2))
        assertNull(validateTodoUpsert("x", null, null, 4))
    }
}
