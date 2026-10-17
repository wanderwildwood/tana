package com.wanderwildwood.tana.nearby

import kotlinx.coroutines.delay

/**
 * Joining another phone's network, kept at for [window] rather than given up on the first time.
 * The other phone may still be starting its network, or starting it again (see [Keeper]); while
 * the phones are still held together the invite is read again between tries, so the newest name
 * and channel are the ones used, and a "not ready" just means waiting a little longer.
 */
internal class Joiner<A>(
    private val join: suspend (Invite) -> A,
    /** The phone held here, read again; [TapRead.Nothing] once it is taken away. */
    private val reread: () -> TapRead,
    private val window: Long = 30_000,
    private val gap: Long = 1500,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The joined network's answer, or null when [window] passed without one. */
    suspend fun run(first: Invite?): A? {
        val until = clock() + window
        var invite = first
        var tried = false
        while (clock() < until) {
            // The first try uses what the tap already read; later ones read it again.
            if (tried || invite == null) {
                when (val r = reread()) {
                    is TapRead.Offered -> invite = r.invite
                    TapRead.NotReady, TapRead.Nothing -> Unit
                }
            }
            tried = true
            val now = invite
            if (now != null) {
                try {
                    return join(now)
                } catch (e: Direct.Failed) {
                    if (e.reason == android.net.wifi.p2p.WifiP2pManager.P2P_UNSUPPORTED) throw e
                }
            }
            if (clock() >= until) break
            delay(gap)
        }
        return null
    }
}
