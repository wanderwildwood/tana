package com.wanderwildwood.tana.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wanderwildwood.tana.R

/** The address of the app's own source code. */
const val SOURCE_URL = "https://github.com/wanderwildwood/tana"

/** What the APK carries that is not this app's own code, and the licence each project declares. */
val LICENCE_COMPONENTS: List<LicenceComponent> = listOf(
    LicenceComponent("AndroidX (core, activity, lifecycle) and Jetpack Compose", "Google", "Apache-2.0", "https://developer.android.com/jetpack"),
    LicenceComponent("MMD, Mudita Mindful Design", "Mudita", "Apache-2.0", "https://github.com/mudita/MMD"),
    LicenceComponent("Lato typeface, inside MMD", "tyPoland Lukasz Dziedzic", "OFL-1.1", "https://www.latofonts.com"),
    LicenceComponent("Kotlin and kotlinx.coroutines", "JetBrains", "Apache-2.0", "https://kotlinlang.org"),
    LicenceComponent("OkHttp and Okio", "Square", "Apache-2.0", "https://square.github.io/okhttp/"),
    LicenceComponent("smbj, the Samba (SMB) client", "Jeroen van Erp", "Apache-2.0", "https://github.com/hierynomus/smbj"),
    LicenceComponent("asn-one, used by smbj", "Jeroen van Erp", "Apache-2.0", "https://github.com/hierynomus/asn-one"),
    LicenceComponent("Bouncy Castle (bcprov), used by smbj for its cryptography", "The Legion of the Bouncy Castle", "MIT", "https://www.bouncycastle.org/java.html"),
    LicenceComponent("MBassador, used by smbj", "Benjamin Diedrichsen", "MIT", "https://github.com/bennidi/mbassador"),
    LicenceComponent("SLF4J API, used by smbj", "QOS.ch", "MIT", "https://www.slf4j.org"),
    LicenceComponent("The LocalSend protocol, which Nearby follows (its own code, no LocalSend code)", "the LocalSend authors", "Apache-2.0", "https://github.com/localsend/protocol"),
    LicenceComponent("Material Symbols icons", "Google", "Apache-2.0", "https://fonts.google.com/icons"),
)

/** Each distinct licence text once: family id and the raw resource. */
val LICENCE_RAW: List<Pair<String, Int>> = listOf(
    "gpl" to R.raw.licence_gpl3,
    "apache" to R.raw.licence_apache2,
    "mit" to R.raw.licence_mit,
    "ofl" to R.raw.licence_ofl,
)

@Composable
fun rememberLicencesData(): LicencesData = LicencesData(
    appName = "Cabinet",
    sourceUrl = SOURCE_URL,
    licenceLine = stringResource(R.string.licences_licence),
    components = LICENCE_COMPONENTS,
    texts = LICENCE_RAW.map { (id, raw) ->
        LicenceText(id, stringResource(when (id) {
            "gpl" -> R.string.licences_full_gpl
            "apache" -> R.string.licences_full_apache
            "mit" -> R.string.licences_full_mit
            "ofl" -> R.string.licences_full_ofl
            else -> R.string.licences_full_head
        }), raw)
    },
)

@Composable
fun rememberLicencesLabels(): LicencesLabels {
    val none = stringResource(R.string.about_no_browser_address, "@@")
    return LicencesLabels(
        title = stringResource(R.string.licences_title),
        back = stringResource(R.string.licences_back),
        sourceHeading = stringResource(R.string.licences_source_head),
        licenceHeading = stringResource(R.string.licences_licence_head),
        builtWithHeading = stringResource(R.string.licences_built_head),
        fullTextsHeading = stringResource(R.string.licences_full_head),
        noBrowser = { address -> none.replace("@@", address) },
    )
}
