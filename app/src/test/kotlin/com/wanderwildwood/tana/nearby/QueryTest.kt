package com.wanderwildwood.tana.nearby

import org.junit.Assert.assertEquals
import org.junit.Test

class QueryTest {
    @Test
    fun aBrokenEscapeIsLeftOutInsteadOfThrowing() {
        assertEquals(mapOf("sessionId" to "a b", "token" to "x"), HttpServer.query("sessionId=a%20b&token=x"))
        assertEquals(mapOf("token" to "x"), HttpServer.query("a=%zz&token=x"))
        assertEquals(emptyMap<String, String>(), HttpServer.query("a=%"))
        assertEquals(emptyMap<String, String>(), HttpServer.query("%"))
    }
}
