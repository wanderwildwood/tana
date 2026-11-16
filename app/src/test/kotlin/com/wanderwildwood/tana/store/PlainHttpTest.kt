package com.wanderwildwood.tana.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlainHttpTest {

    @Test
    fun httpsIsAlwaysFine() {
        assertTrue(DavStore.plainHttpAllowed("https://cloud.example.org/"))
    }

    @Test
    fun httpOnAHomeOrVpnNetworkIsFine() {
        for (a in listOf(
            "http://192.168.1.5/", "http://10.0.0.2:8080/", "http://172.20.0.4/", "http://100.101.102.103/",
            "http://[fd12::5]/", "http://nextcloud/", "http://cloud.local/", "http://box.lan/",
            "http://pc.tail1234.ts.net/", "http://localhost:8080/",
        )) assertTrue(a, DavStore.plainHttpAllowed(a))
    }

    @Test
    fun httpToTheInternetIsRefused() {
        for (a in listOf(
            "http://cloud.example.org/", "http://8.8.8.8/", "http://203.0.113.9:8080/", "http://172.32.0.1/",
            "http://evil.local.example.org/", "http://[2001:db8::1]/", "not an address",
        )) assertFalse(a, DavStore.plainHttpAllowed(a))
    }
}
