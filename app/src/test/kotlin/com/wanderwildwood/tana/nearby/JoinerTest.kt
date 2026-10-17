package com.wanderwildwood.tana.nearby

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.ArrayDeque

class JoinerTest {
    private val first = Invite("DIRECT-ab-A", "abcdefgh", 2437, "A", 53317, "A".repeat(64))
    private val fresher = first.copy(frequency = 5180)

    private fun reads(vararg r: TapRead): () -> TapRead {
        val q = ArrayDeque(r.toList())
        return { if (q.isEmpty()) TapRead.Nothing else q.poll() }
    }

    @Test
    fun aJoinThatFailsIsTriedAgainWithTheInviteReadAgain() = runBlocking {
        val tried = mutableListOf<Invite>()
        val j = Joiner(
            join = { i -> tried += i; if (tried.size < 3) throw Direct.Failed(2) else "joined" },
            reread = reads(TapRead.Offered(fresher)),
            window = 5000, gap = 5,
        )
        assertEquals("joined", j.run(first))
        assertEquals(listOf(first, fresher, fresher), tried)
    }

    @Test
    fun notReadyIsWaitedOnUntilTheNetworkIsUp() = runBlocking {
        val tried = mutableListOf<Invite>()
        val j = Joiner(
            join = { i -> tried += i; "joined" },
            reread = reads(TapRead.NotReady, TapRead.NotReady, TapRead.Offered(first)),
            window = 5000, gap = 5,
        )
        assertEquals("joined", j.run(null))
        assertEquals(listOf(first), tried)
    }

    @Test
    fun itGivesUpWhenTheTimeIsUp() = runBlocking {
        var now = 0L
        var tries = 0
        val j = Joiner<String>(
            join = { tries++; now += 4000; throw Direct.Failed(2) },
            reread = { TapRead.Nothing },
            window = 30_000, gap = 1, clock = { now },
        )
        assertNull(j.run(first))
        assertEquals(8, tries)
    }

    @Test
    fun nothingEverOfferedIsGivenUpOnToo() = runBlocking {
        var now = 0L
        val j = Joiner<String>(join = { "joined" }, reread = { now += 1000; TapRead.NotReady }, window = 3000, gap = 1, clock = { now })
        assertNull(j.run(null))
    }
}
