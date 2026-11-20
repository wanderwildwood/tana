package com.wanderwildwood.tana.store

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** What must be settled before a server is used (Home asked again) is settled before its store is handed out. */
class StoresGateTest {
    private val home = Server(id = "home", name = "Cloud", host = "https://cloud.example.org/remote.php/dav/files/ada/", share = "", user = "ada", password = "revoked", kind = Server.DAV)

    @After fun clear() {
        Stores.beforeServer = null
        Stores.setServers(emptyList())
    }

    @Test fun theStoreHandedOutIsTheOneAfterTheCheck() {
        Stores.setServers(listOf(home))
        Stores.beforeServer = { Stores.setServers(listOf(home.copy(password = "new"))) }
        assertEquals("new", (Stores.get(home.storeId) as ServerStore).server.password)
    }

    @Test fun aServerTheCheckTookAwayIsNotHandedOut() {
        Stores.setServers(listOf(home))
        Stores.beforeServer = { Stores.setServers(emptyList()) }
        val e = runCatching { Stores.get(home.storeId) }.exceptionOrNull()
        assertEquals(StoreException.Reason.GONE, (e as StoreException).reason)
    }

    @Test fun onlyServersWaitForIt() {
        val asked = mutableListOf<String>()
        Stores.beforeServer = { asked += it }
        Stores.get(LocalStore.ID)
        runCatching { Stores.get(home.storeId) }
        assertEquals(listOf(home.storeId), asked)
    }
}
