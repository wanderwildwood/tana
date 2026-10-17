package com.wanderwildwood.tana.nearby

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class KeeperTest {
    /** A phone's Wi-Fi Direct, as far as the keeper sees it: a network that can be made to go. */
    private class FakeGroups(var failFirst: Int = 0, var rename: String? = null) : Groups {
        @Volatile var up: Group? = null
        val created = CopyOnWriteArrayList<Pair<String, String>>()

        override suspend fun create(name: String, passphrase: String): Group {
            delay(20)
            if (failFirst > 0) {
                failFirst--
                throw Direct.Failed(2)
            }
            created += name to passphrase
            return Group(rename ?: name, passphrase, 2437).also { up = it }
        }

        override suspend fun current(): Group? = up
    }

    private suspend fun waitFor(what: () -> Boolean) {
        repeat(200) {
            if (what()) return
            delay(10)
        }
        throw AssertionError("never happened")
    }

    @Test
    fun aNetworkThatGoesIsStartedAgainUnderTheSameNameAndPassword() = runBlocking {
        val g = FakeGroups()
        val seen = CopyOnWriteArrayList<Kept>()
        val k = Keeper(g, "DIRECT-ab-Kompakt", "abcdefgh", { seen += it }, poll = 50, pause = 10)
        val job = launch { k.run() }
        waitFor { seen.lastOrNull() is Kept.Up }
        // Taken down by Android, without a word: found by asking.
        g.up = null
        waitFor { g.created.size == 2 && seen.lastOrNull() is Kept.Up }
        // Taken down, and Android says so (the broadcast).
        k.groupRemoved()
        waitFor { g.created.size == 3 && seen.lastOrNull() is Kept.Up }
        job.cancel()
        assertTrue(g.created.all { it == ("DIRECT-ab-Kompakt" to "abcdefgh") })
        assertEquals(Kept.Starting, seen.first())
        assertTrue(seen.contains(Kept.Down("DIRECT-ab-Kompakt", "abcdefgh")))
    }

    @Test
    fun whatIsReportedIsTheNameTheNetworkReallyHas() = runBlocking {
        val g = FakeGroups(rename = "DIRECT-zz-Other")
        val seen = CopyOnWriteArrayList<Kept>()
        val job = launch { Keeper(g, "DIRECT-ab-Kompakt", "abcdefgh", { seen += it }, poll = 50, pause = 10).run() }
        waitFor { seen.lastOrNull() is Kept.Up }
        job.cancel()
        assertEquals("DIRECT-zz-Other", (seen.last() as Kept.Up).group.network)
    }

    @Test
    fun aFailedStartIsTriedAgainThenGivenUp() = runBlocking {
        val g = FakeGroups(failFirst = 2)
        val seen = CopyOnWriteArrayList<Kept>()
        val job = launch { Keeper(g, "n", "abcdefgh", { seen += it }, poll = 50, pause = 10).run() }
        waitFor { seen.lastOrNull() is Kept.Up }
        job.cancel()

        val never = FakeGroups(failFirst = 99)
        val told = CopyOnWriteArrayList<Kept>()
        Keeper(never, "n", "abcdefgh", { told += it }, poll = 50, pause = 10, attempts = 3).run()
        assertEquals(Kept.Failed(2), told.last())
    }

    @Test
    fun aRemovalHeardWhileStartingIsNotTakenForTheNewOne() = runBlocking {
        val g = FakeGroups()
        val seen = CopyOnWriteArrayList<Kept>()
        val k = Keeper(g, "n", "abcdefgh", { seen += it }, poll = 50, pause = 10)
        k.groupRemoved()
        val job = launch { k.run() }
        waitFor { seen.lastOrNull() is Kept.Up }
        delay(200)
        job.cancel()
        assertEquals(1, g.created.size)
    }
}
