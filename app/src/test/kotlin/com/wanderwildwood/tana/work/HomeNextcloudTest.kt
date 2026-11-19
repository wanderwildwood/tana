package com.wanderwildwood.tana.work

import com.wanderwildwood.tana.store.Server
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeNextcloudTest {
    private val ada = HomeAccount.Held("https://cloud.example.org/", "ada lovelace", "pw", "s1")
    private val home = HomeNextcloud.server(ada)

    @Test fun homeIsTheUsersFilesFolder() {
        assertEquals("https://cloud.example.org/remote.php/dav/files/ada%20lovelace/", home.host)
        assertEquals(HomeNextcloud.ID, home.id)
        assertEquals(HomeNextcloud.STORE_ID, home.storeId)
        assertEquals(Server.DAV, home.kind)
        assertEquals("cloud.example.org", home.name)
    }

    @Test fun shownWithoutScheme() {
        assertEquals("cloud.example.org", HomeNextcloud.shown("https://cloud.example.org/"))
        assertEquals("10.0.0.2:8080/nc", HomeNextcloud.shown("http://10.0.0.2:8080/nc"))
    }

    @Test fun homeKeepsItsPlaceOrGoesLast() {
        val a = Server("a", "A", "smb-host", "music")
        val b = Server("b", "B", "https://x/", "", kind = Server.DAV)
        val old = home.copy(password = "old")
        assertEquals(listOf(a, home, b), HomeNextcloud.withHome(listOf(a, old, b), home))
        assertEquals(listOf(a, b, home), HomeNextcloud.withHome(listOf(a, b), home))
    }

    @Test fun replacesTheOneChangedAndOwnSignInsToTheSameFolder() {
        val samba = Server("s", "S", "host", "share")
        val same = Server("n", "N", home.host.trimEnd('/'), "", user = "ada lovelace", password = "own", kind = Server.DAV)
        val otherUser = same.copy(id = "o", user = "bob")
        val changed = Server("c", "C", "https://elsewhere/", "", user = "ada", kind = Server.DAV)
        val all = listOf(samba, same, otherUser, changed, home)
        assertEquals(listOf(same, changed), HomeNextcloud.replaced(all, home, "c"))
        assertEquals(listOf(same), HomeNextcloud.replaced(all, home, null))
        // A Samba share is never replaced, even when it is the one on screen.
        assertEquals(listOf(same), HomeNextcloud.replaced(all, home, "s"))
    }
}
