package com.wanderwildwood.tana.ui

import android.content.Context
import androidx.annotation.RawRes
import com.wanderwildwood.tana.R

/** A third-party part of the app: what it is, its licence, and where it lives. */
data class Component(val name: String, val licence: String, val link: String)

/**
 * What the APK carries that is not this app's own: the libraries it is built from, the
 * typeface, the icons and the protocol Nearby follows. Each distinct licence's full text is
 * shown once below the list.
 */
object Licences {

    const val SOURCE = "https://github.com/wanderwildwood/tana"

    const val APACHE = "Apache-2.0"
    const val MIT = "MIT"
    const val OFL = "OFL-1.1"

    val components: List<Component> = listOf(
        Component("AndroidX (core, activity, lifecycle) and Jetpack Compose", APACHE, "https://developer.android.com/jetpack"),
        Component("MMD, Mudita Mindful Design", APACHE, "https://github.com/mudita/MMD"),
        Component("Lato typeface, inside MMD (Copyright 2010-2014 tyPoland Lukasz Dziedzic)", OFL, "https://www.latofonts.com"),
        Component("Kotlin and kotlinx.coroutines", APACHE, "https://kotlinlang.org"),
        Component("OkHttp and Okio", APACHE, "https://square.github.io/okhttp/"),
        Component("smbj, the Samba (SMB) client by Jeroen van Erp", APACHE, "https://github.com/hierynomus/smbj"),
        Component("asn-one, used by smbj", APACHE, "https://github.com/hierynomus/asn-one"),
        Component("Bouncy Castle (bcprov), used by smbj for its cryptography", MIT, "https://www.bouncycastle.org/java.html"),
        Component("MBassador, used by smbj", MIT, "https://github.com/bennidi/mbassador"),
        Component("SLF4J API, used by smbj", MIT, "https://www.slf4j.org"),
        Component("The LocalSend protocol, which Nearby follows (its own code, no LocalSend code)", APACHE, "https://github.com/localsend/protocol"),
        Component("Material Symbols icons", APACHE, "https://fonts.google.com/icons"),
    )

    /** The licence texts shown in full after the list: one per distinct licence the list names. */
    @RawRes
    fun textFor(licence: String): Int = when (licence) {
        APACHE -> R.raw.licence_apache2
        MIT -> R.raw.licence_mit
        OFL -> R.raw.licence_ofl
        else -> throw IllegalArgumentException("no text for $licence")
    }

    fun distinctLicences(): List<String> = components.map { it.licence }.distinct()

    /** A licence text as paragraphs: a blank line ends one, and the line breaks inside run together. */
    fun paragraphs(text: String): List<String> =
        text.split(Regex("\\r?\\n[ \\t]*\\r?\\n"))
            .map { it.trim().replace(Regex("\\s*\\r?\\n\\s*"), " ") }
            .filter { it.isNotEmpty() }

    fun read(context: Context, @RawRes id: Int): List<String> =
        paragraphs(context.resources.openRawResource(id).bufferedReader().use { it.readText() })
}
