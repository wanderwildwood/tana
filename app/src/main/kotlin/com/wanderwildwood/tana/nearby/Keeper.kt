package com.wanderwildwood.tana.nearby

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** A Wi-Fi Direct network as Android reports it: up, with this phone as the one that started it. */
internal data class Group(val network: String, val passphrase: String, val frequency: Int)

/** Starting a network, and asking whether it is still there. [Direct] is the real one. */
internal interface Groups {
    /** Started under [name] and [passphrase], or [Direct.Failed]. */
    suspend fun create(name: String, passphrase: String): Group

    /** The network this phone started, or null when there is none. */
    suspend fun current(): Group?
}

/** How the network this phone keeps is, from moment to moment. */
internal sealed interface Kept {
    data object Starting : Kept
    data class Up(val group: Group) : Kept
    /** It went, and is being started again under the same name and password. */
    data class Down(val network: String, val passphrase: String) : Kept
    data class Failed(val reason: Int) : Kept
}

/**
 * Keeps a network up for as long as it is wanted. Some phones (MediaTek's Android 12 among them)
 * take a network down about ten seconds after it starts if nobody has joined, and again whenever
 * the last one leaves; nothing an app can set stops that. So the network is watched, and started
 * again under the same name and password each time it goes, so a phone that was handed those by a
 * tap can still join. If Android ever starts it under another name, what is reported (and so what
 * a tap hands over) is the name it really has.
 *
 * Android's word that the network changed arrives by [removed]; it is also asked every [poll], in
 * case that word never comes.
 */
internal class Keeper(
    private val groups: Groups,
    private val name: String,
    private val passphrase: String,
    private val report: (Kept) -> Unit,
    private val poll: Long = 3000,
    /** A short wait before starting again, so Android has finished taking the old one down. */
    private val pause: Long = 1000,
    /** Starts that fail one after another before giving up. */
    private val attempts: Int = 5,
) {
    private val removed = Channel<Unit>(Channel.CONFLATED)

    /** Android said the network is gone. Only heard while it is up; ignored while starting. */
    fun groupRemoved() {
        removed.trySend(Unit)
    }

    /** Runs until cancelled, or until starting has failed [attempts] times in a row. */
    suspend fun run() {
        var failed = 0
        var first = true
        while (true) {
            report(if (first) Kept.Starting else Kept.Down(name, passphrase))
            val group = try {
                groups.create(name, passphrase)
            } catch (e: Direct.Failed) {
                if (++failed >= attempts) return report(Kept.Failed(e.reason))
                delay(pause * failed)
                continue
            }
            failed = 0
            first = false
            // Whatever was said while it was starting is about the old one.
            removed.tryReceive()
            var shown = group
            report(Kept.Up(group))
            while (true) {
                val told = withTimeoutOrNull(poll) { removed.receive() } != null
                val now = if (told) null else groups.current()
                if (now == null) break
                if (now != shown) {
                    shown = now
                    report(Kept.Up(now))
                }
            }
            delay(pause)
        }
    }
}
