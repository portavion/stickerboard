package com.portavion.stickerboard

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal sealed interface Block {
    data class Para(val text: String) : Block
    data class Bullets(val items: List<String>) : Block
}

internal data class Section(val heading: String, val blocks: List<Block>)

internal data class PolicyDoc(
    val effective: LocalDate,
    val webCopy: String,
    val contact: String,
    val dataSafety: DataSafety,
    val body: List<Section>,
) {
    val sections: List<Section> get() = body + dataSafety.asSection()
}

internal data object CollectedNothing
internal data object TransitNone
internal data object DeletionUninstallOnly

internal data class DataSafety(
    val collected: CollectedNothing = CollectedNothing,
    val transit: TransitNone = TransitNone,
    val independentSecurityReview: Boolean = false,
    val deletion: DeletionUninstallOnly = DeletionUninstallOnly,
) {
    fun asSection(): Section = Section(
        "What Google Play shows",
        listOf(Block.Bullets(consoleAnswers())),
    )

    fun consoleAnswers(): List<String> = listOf(
        "Data collected: none. Data shared: none.",
        "Encryption in transit: not applicable, there is no transit.",
        "Data deletion request: not applicable, nothing leaves your phone.",
        "Independent security review: ${if (independentSecurityReview) "yes" else "no"}.",
    )
}

internal enum class Sdk(val marker: String, val disclosure: String) {
    MlKitSubjectSegmentation(
        "com.google.mlkit.vision.segmentation.subject.SubjectSegmentation",
        "ML Kit subject segmentation runs on the device. Play services may download the model. stickerboard uploads nothing.",
    ),
    FirebaseAnalytics(
        "com.google.firebase.analytics.FirebaseAnalytics",
        "Firebase Analytics collects app interaction and device identifiers.",
    ),
    FirebaseCrashlytics(
        "com.google.firebase.crashlytics.FirebaseCrashlytics",
        "Crashlytics collects crash logs and device identifiers.",
    ),
    PlayBilling(
        "com.android.billingclient.api.BillingClient",
        "Play Billing handles purchases. Google processes payment data.",
    ),
    AdMob(
        "com.google.android.gms.ads.MobileAds",
        "AdMob collects advertising identifiers.",
    ),
}

internal data class Footprint(val permissions: Set<String>, val present: Set<Sdk>) {
    val absent: Set<Sdk> get() = Sdk.entries.toSet() - present

    companion object {
        private val permissionName = Regex("""<uses-permission\b[^>]*android:name="([^"]+)"""")

        fun observe(manifest: File, classpath: ClassLoader): Footprint {
            val permissions = permissionName.findAll(manifest.readText())
                .map { it.groupValues[1] }
                .toSet()
            val present = Sdk.entries.filter { sdk ->
                try {
                    Class.forName(sdk.marker, false, classpath)
                    true
                } catch (_: ClassNotFoundException) {
                    false
                }
            }.toSet()
            return Footprint(permissions, present)
        }
    }
}

internal val stickerboardPolicy = PolicyDoc(
    effective = LocalDate.of(2026, 9, 9),
    webCopy = "https://portavion.github.io/stickerboard/privacy/",
    contact = "portalier.g@gmail.com",
    dataSafety = DataSafety(),
    body = listOf(
        Section(
            "The short version",
            listOf(
                Block.Bullets(
                    listOf(
                        "stickerboard has no account, no servers, and no analytics. It asks for no Android permissions at all.",
                        "Your stickers stay in stickerboard's private storage on your phone.",
                        "Sending a sticker hands that one image to the chat app you chose, and nowhere else.",
                        "Uninstalling deletes every sticker.",
                    )
                )
            ),
        ),
        Section(
            "Where your stickers live",
            listOf(
                Block.Para("Your stickers live in a folder only stickerboard can open."),
                Block.Para("Android backup is off, so stickers are not copied into a cloud backup."),
            ),
        ),
        Section(
            "Adding stickers",
            listOf(
                Block.Para("You add stickers with the photo picker, by sharing an image to stickerboard, or by pasting."),
                Block.Para("Each path copies the image you picked into stickerboard's own folder. The original is untouched."),
                Block.Para("stickerboard does not browse your gallery. The system picker hands over only what you selected. That is why there is no photo permission."),
            ),
        ),
        Section(
            "Sending a sticker",
            listOf(
                Block.Para("Tapping a sticker gives the chat app temporary read access to that one image file, through Android's file-sharing mechanism."),
                Block.Para("stickerboard does not upload it anywhere. What the chat app does with it afterwards is that app's business and its own privacy policy."),
                Block.Para("If the chat app cannot take a sticker directly, stickerboard falls back to the clipboard."),
                Block.Para("A clipboard entry can be read by whichever app you paste into next, and on some Android versions by the system clipboard UI."),
                Block.Para("That is one sticker at a time, and only when you tapped send."),
            ),
        ),
        Section(
            "Backing up to a zip",
            listOf(
                Block.Para("Export writes a zip to the location you chose in the system file picker. That can be Downloads, Drive, a USB stick, or whatever you picked."),
                Block.Para("Once it is there, stickerboard has no say over it. Its privacy is your file manager's or your cloud provider's."),
                Block.Para("Import reads a zip you choose. stickerboard never writes to shared storage on its own."),
            ),
        ),
        Section(
            "The cutout tool",
            listOf(
                Block.Para("Cutouts run on your device, with Google's ML Kit subject segmentation."),
                Block.Para("The first cutout may make Google Play services download the segmentation model to your phone."),
                Block.Para("That download follows Google's privacy policy. It carries no sticker of yours."),
                Block.Para("Your image is never sent anywhere for this. The pixels go from stickerboard's folder to on-device code and back."),
            ),
        ),
        Section(
            "Deleting your stickers",
            listOf(
                Block.Para("Delete one with the × on its thumbnail."),
                Block.Para("Delete all of them by uninstalling stickerboard. There is no copy anywhere else."),
            ),
        ),
        Section(
            "Children",
            listOf(
                Block.Para("stickerboard is not directed at children. It collects nothing from anyone, and it has no age-gated surface."),
            ),
        ),
        Section(
            "Changes to this notice",
            listOf(
                Block.Para("If a future version of stickerboard collects anything, this page changes in the same release that adds it, and the effective date at the top moves."),
            ),
        ),
        Section(
            "Contact",
            listOf(
                Block.Para("portalier.g@gmail.com"),
            ),
        ),
    ),
)

internal fun PolicyDoc.toHtml(): String = buildString {
    append("<!DOCTYPE html>\n")
    append("<html lang=\"en\">\n")
    append("<head>\n")
    append("<meta charset=\"utf-8\">\n")
    append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
    append("<title>stickerboard privacy notice</title>\n")
    append("<style>\n")
    append("body{margin:1.5rem auto;max-width:40rem;padding:0 1rem;color:#111;background:#fff;line-height:1.5}\n")
    append("h1{font-size:1.5rem;font-weight:700}\n")
    append("h2{font-size:1.15rem;margin-top:1.75rem}\n")
    append(".effective,.canonical{color:#555}\n")
    append("ul{padding-left:1.25rem}\n")
    append("a{color:#06c}\n")
    append("</style>\n")
    append("</head>\n")
    append("<body>\n")
    append("<h1>stickerboard privacy notice</h1>\n")
    append("<p class=\"effective\">Effective ${effective.toString().escapeHtml()}</p>\n")
    sections.forEach { section ->
        append("<h2>${section.heading.escapeHtml()}</h2>\n")
        section.blocks.forEach { block ->
            when (block) {
                is Block.Para -> append(paraHtml(block.text))
                is Block.Bullets -> {
                    append("<ul>\n")
                    block.items.forEach { item ->
                        append("<li>${item.escapeHtml()}</li>\n")
                    }
                    append("</ul>\n")
                }
            }
        }
    }
    append("<p class=\"canonical\">Web copy: <a href=\"${webCopy.escapeHtml()}\">${webCopy.escapeHtml()}</a></p>\n")
    append("</body>\n")
    append("</html>\n")
}

private fun PolicyDoc.paraHtml(text: String): String {
    val escaped = text.escapeHtml()
    return if (text == contact) {
        "<p><a href=\"mailto:$escaped\">$escaped</a></p>\n"
    } else {
        "<p>$escaped</p>\n"
    }
}

internal fun PolicyDoc.toConsoleChecklist(footprint: Footprint): String = buildString {
    append("# Play Data safety\n\n")
    append(webCopy)
    append("\n\n")
    append("## Answers\n\n")
    dataSafety.consoleAnswers().forEach { line ->
        append("- ")
        append(line)
        append('\n')
    }
    append("\n## SDKs present\n\n")
    val present = Sdk.entries.filter { it in footprint.present }
    if (present.isEmpty()) {
        append("- none\n")
    } else {
        present.forEach { sdk ->
            append("- ")
            append(sdk.disclosure)
            append('\n')
        }
    }
    append("\n## SDKs absent\n\n")
    val absent = Sdk.entries.filter { it in footprint.absent }
    if (absent.isEmpty()) {
        append("- none\n")
    } else {
        absent.forEach { sdk ->
            append("- not in this APK. ")
            append(sdk.disclosure)
            append('\n')
        }
    }
}

private fun String.escapeHtml(): String = buildString(length) {
    for (c in this@escapeHtml) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(c)
        }
    }
}

/**
 * The in-app privacy notice: a summary card of the short version up top, then every other
 * section folded under its heading. The back arrow calls [onClose]; Web copy opens the hosted policy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val policy = stickerboardPolicy
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val noBrowser = stringResource(R.string.privacy_no_browser)
    val locale = LocalConfiguration.current.locales[0]
    val effective = remember(locale) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(policy.effective)
    }
    // The first section is "The short version"; its bullets fill the summary card.
    val summary = policy.sections.first().blocks.filterIsInstance<Block.Bullets>().flatMap { it.items }
    val folded = policy.sections.drop(1)
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.privacy_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.privacy_back))
                    }
                },
                actions = {
                    TextButton(onClick = {
                        if (!openUrl(context, policy.webCopy)) {
                            scope.launch { snackbars.showSnackbar(noBrowser) }
                        }
                    }) {
                        Icon(
                            painterResource(R.drawable.ic_pv_open_in_new),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.privacy_web_copy))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp).fillMaxWidth(),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.privacy_summary_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                    summary.forEach { item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(item, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Text(
                stringResource(R.string.privacy_effective, effective),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
            )
            Column(Modifier.padding(horizontal = 16.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                folded.forEach { section ->
                    val open = section.heading in expanded
                    val turn by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
                    val state = stringResource(if (open) R.string.privacy_expanded else R.string.privacy_collapsed)
                    Column(Modifier.animateContentSize()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 50.dp)
                                .clickable(role = Role.Button) {
                                    expanded = if (open) expanded - section.heading else expanded + section.heading
                                }
                                .semantics { stateDescription = state },
                        ) {
                            Text(
                                section.heading,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                            )
                            Icon(
                                Icons.Rounded.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.rotate(turn),
                            )
                        }
                        if (open) {
                            Column(
                                Modifier.padding(bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                section.blocks.forEach { block ->
                                    when (block) {
                                        is Block.Para -> Text(block.text, style = MaterialTheme.typography.bodyMedium)
                                        is Block.Bullets -> block.items.forEach { item ->
                                            Text("\u2022  $item", style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

internal fun openUrl(context: Context, url: String): Boolean =
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
