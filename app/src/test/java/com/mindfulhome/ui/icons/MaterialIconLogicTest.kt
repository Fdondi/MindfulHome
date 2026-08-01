package com.mindfulhome.ui.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MaterialIconLogicTest {

    @Test
    fun parseMaterialIconCodepointLine() {
        assertNull(parseMaterialIconCodepointLine(""))
        assertNull(parseMaterialIconCodepointLine("# comment"))
        assertNull(parseMaterialIconCodepointLine("nospace"))
        assertNull(parseMaterialIconCodepointLine("bad zz"))
        assertEquals("home" to 0xe88a, parseMaterialIconCodepointLine("home e88a"))
        assertEquals("foo" to 0x10, parseMaterialIconCodepointLine("  foo 10  "))
    }
}
