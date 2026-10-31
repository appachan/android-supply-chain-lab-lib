package com.github.appachan.supplychainlab.lib

import org.junit.Assert.assertEquals
import org.junit.Test

class LabGreetingTest {
    @Test
    fun `message contains the given name`() {
        assertEquals("Hello, lab", LabGreeting.message("lab"))
    }
}
