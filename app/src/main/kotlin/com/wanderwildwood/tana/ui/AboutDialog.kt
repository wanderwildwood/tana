package com.wanderwildwood.tana.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.tana.BuildConfig
import com.wanderwildwood.tana.R

/**
 * What this is, what it reaches, and where the source is.
 *
 * The line about servers is here because a stranger cannot safely assume the answer: a file
 * manager that asks for the network could be sending anything anywhere, and this one sends
 * only to the servers the reader typed in.
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf("") }
    val gpl = remember { Licences.read(context, R.raw.licence_gpl3) }
    val texts = remember { Licences.distinctLicences().associateWith { Licences.read(context, Licences.textFor(it)) } }
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(
            text = if (page.isEmpty()) stringResource(R.string.about_title, BuildConfig.VERSION_NAME) else stringResource(
                when (page) {
                    PRIVACY -> R.string.about_privacy_title
                    TERMS -> R.string.about_terms_title
                    else -> R.string.about_licences_title
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )

        Spacer(Modifier.height(14.dp))
        // The text may outgrow the dialog; it pages by whole rows and Back or Close stays below it.
        PagedColumn(Modifier.weight(1f, fill = false).heightIn(max = 440.dp)) {
            when (page) {
                PRIVACY -> {
                    item { TextMMD(text = stringResource(R.string.about_privacy_servers), style = MaterialTheme.typography.labelSmall) }
                    item { TextMMD(text = stringResource(R.string.about_privacy_phone), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp)) }
                    item { TextMMD(text = stringResource(R.string.about_privacy_nearby), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp)) }
                    item { TextMMD(text = stringResource(R.string.about_privacy_picker), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp)) }
                    item { TextMMD(text = stringResource(R.string.about_privacy_close), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp)) }
                }
                TERMS -> item { TextMMD(text = stringResource(R.string.about_terms), style = MaterialTheme.typography.labelSmall) }
                LICENCES -> {
                    item { Heading(R.string.licences_source) }
                    item {
                        LinkRow(Licences.SOURCE.removePrefix("https://")) {
                            openLink(context, Licences.SOURCE, context.getString(R.string.about_no_browser_address, Licences.SOURCE))
                        }
                    }
                    item { Heading(R.string.licences_licence) }
                    item { TextMMD(text = stringResource(R.string.licences_licence_line), style = MaterialTheme.typography.labelSmall) }
                    items(gpl) { Words(it) }
                    item { Heading(R.string.licences_built_with) }
                    item { TextMMD(text = stringResource(R.string.licences_built_with_note), style = MaterialTheme.typography.labelSmall) }
                    items(Licences.components) { c ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Words(c.name + ", " + c.licence)
                            LinkRow(c.link.removePrefix("https://")) {
                                openLink(context, c.link, context.getString(R.string.about_no_browser_address, c.link))
                            }
                        }
                    }
                    for (licence in Licences.distinctLicences()) {
                        item { TextMMD(text = stringResource(R.string.licences_text_of, licence), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)) }
                        items(texts.getValue(licence)) { Words(it) }
                    }
                }
                else -> {
                    item { TextMMD(text = stringResource(R.string.about_body), style = MaterialTheme.typography.labelSmall) }
                    item { AboutRow(R.string.about_privacy_title) { page = PRIVACY } }
                    item { AboutRow(R.string.about_terms_title) { page = TERMS } }
                    item { AboutRow(R.string.about_licences_title) { page = LICENCES } }
                }
            }
        }

        if (page.isEmpty()) {
            Spacer(Modifier.height(14.dp))
            Llama()
        }

        Spacer(Modifier.height(if (page.isEmpty()) 18.dp else 8.dp))
        OutlinedButtonMMD(
            onClick = { if (page.isEmpty()) onDismiss() else page = "" },
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { TextMMD(text = stringResource(if (page.isEmpty()) R.string.about_close else R.string.about_back), style = MaterialTheme.typography.bodySmall) }
    }
}

private const val PRIVACY = "privacy"
private const val TERMS = "terms"
private const val LICENCES = "licences"

@Composable
private fun AboutRow(text: Int, onClick: () -> Unit) {
    TextMMD(
        text = stringResource(text),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically),
    )
}

@Composable
private fun LinkRow(text: String, onClick: () -> Unit) {
    TextMMD(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically),
    )
}

@Composable
private fun Words(text: String) {
    TextMMD(text = text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun Heading(id: Int) {
    TextMMD(
        text = stringResource(id),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
    )
}

/**
 * A llama at the foot of the About, which opens the page a donation goes to.
 * The site's address sits at the start of the same line and opens the site; the llama and its words open the page.
 *
 * Three words rather than an address: a verb and an object, so what happens when you press
 * them is not a surprise even though the page is not named. The drawing is his own, and it is
 * ink rather than an emoji, which is a colour glyph and reaches the panel as a pale smudge.
 *
 * Straight to the checkout rather than the donation page on the site, which only leads there
 * anyway. A phone with nothing registered for a web address throws, and this says so out loud
 * rather than swallowing it and leaving a press that does nothing with no explanation.
 */
@Composable
private fun Llama() {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        TextMMD(
            text = "wanderthe.dev",
            style = MaterialTheme.typography.labelSmall,
            // The site's address opens the site, the way the llama beside it opens its page.
            modifier = Modifier
                .clickable { openLink(context, "https://wanderthe.dev", context.getString(R.string.about_no_browser_address, "wanderthe.dev")) }
                .padding(vertical = 4.dp),
        )
        Spacer(Modifier.width(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { openLink(context, "https://square.link/u/AGu8oT10", context.getString(R.string.about_no_browser_address, "square.link/u/AGu8oT10")) }
                .padding(vertical = 4.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.llama),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(6.dp))
            TextMMD(
                text = stringResource(R.string.about_feed_the_llamas),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
