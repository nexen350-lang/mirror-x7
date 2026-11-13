package com.scrcpy.remote.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.scrcpy.remote.BuildConfig
import com.scrcpy.remote.R
import com.scrcpy.remote.scrcpy.ScrcpyServerVersion
import com.scrcpy.remote.ui.design.InsetDivider
import com.scrcpy.remote.ui.design.InsetGroup
import com.scrcpy.remote.ui.design.InsetRow

/** Optional public publisher metadata from `publisher.properties`; empty in local builds. */
internal data class PublisherInfo(val name: String, val email: String, val privacyPolicyUrl: String) {
    companion object {
        fun fromBuild() = PublisherInfo(BuildConfig.PUBLISHER_NAME, BuildConfig.PUBLISHER_EMAIL, BuildConfig.PRIVACY_POLICY_URL)
    }
}

private enum class AppInfoPage { MAIN, PRIVACY, LICENSES, LICENSE }

/**
 * App info, with the privacy policy and the open-source licenses as pages of the same dialog, so
 * a page survives recreation and Back steps out of it one page at a time.
 */
@Composable
internal fun AppInfoDialog(publisher: PublisherInfo = PublisherInfo.fromBuild(), onDismiss: () -> Unit) {
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val maxContentHeight = with(LocalDensity.current) { windowHeight.toDp() * 0.55f }
    var page by rememberSaveable { mutableStateOf(AppInfoPage.MAIN) }
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    val context = LocalContext.current
    val notices = remember { runCatching { OpenSourceNotices.parse(context.readAsset(OpenSourceNotices.INDEX)) }.getOrNull() }
    val component = notices?.components?.getOrNull(selected)
    // A license that is no longer listed falls back to the list.
    val shown = if (page == AppInfoPage.LICENSE && component == null) AppInfoPage.LICENSES else page
    val back: () -> Unit = when (shown) {
        AppInfoPage.MAIN -> onDismiss
        AppInfoPage.LICENSE -> { { page = AppInfoPage.LICENSES } }
        else -> { { page = AppInfoPage.MAIN } }
    }
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = back,
        title = {
            Text(when (shown) {
                AppInfoPage.MAIN -> stringResource(R.string.app_info_title)
                AppInfoPage.PRIVACY -> stringResource(R.string.app_info_privacy_policy)
                AppInfoPage.LICENSES -> stringResource(R.string.app_info_licenses)
                AppInfoPage.LICENSE -> component?.name.orEmpty()
            })
        },
        text = {
            // A page opens at its top, rather than wherever the previous page had been scrolled to.
            key(shown, selected) {
                Column(
                    Modifier.heightIn(max = maxContentHeight)
                        .verticalScroll(rememberScrollState()).testTag("app-info-body"),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (shown) {
                        AppInfoPage.MAIN -> MainPage(publisher,
                            onPrivacy = { page = AppInfoPage.PRIVACY }, onLicenses = { page = AppInfoPage.LICENSES })
                        AppInfoPage.PRIVACY -> PrivacyPage(publisher)
                        AppInfoPage.LICENSES -> LicensesPage(notices) { index -> selected = index; page = AppInfoPage.LICENSE }
                        AppInfoPage.LICENSE -> component?.let { LicensePage(it) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = back) {
                Text(stringResource(if (shown == AppInfoPage.MAIN) R.string.app_info_back_to_settings else R.string.app_info_back))
            }
        },
        dismissButton = if (shown == AppInfoPage.PRIVACY && publisher.privacyPolicyUrl.isNotBlank()) {
            {
                TextButton(onClick = { runCatching { uriHandler.openUri(publisher.privacyPolicyUrl) } }) {
                    Text(stringResource(R.string.app_info_view_online))
                }
            }
        } else null,
    )
}

@Composable
private fun MainPage(publisher: PublisherInfo, onPrivacy: () -> Unit, onLicenses: () -> Unit) {
    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.app_info_independent), style = MaterialTheme.typography.bodySmall)
    InsetGroup {
        InsetRow(stringResource(R.string.app_info_version),
            detail = stringResource(R.string.app_info_version_value, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
        InsetDivider()
        InsetRow(stringResource(R.string.app_info_package), detail = BuildConfig.APPLICATION_ID)
        InsetDivider()
        InsetRow(stringResource(R.string.app_info_build), detail = BuildConfig.BUILD_TYPE)
        InsetDivider()
        InsetRow(stringResource(R.string.app_info_server), detail = stringResource(R.string.app_info_server_value,
            ScrcpyServerVersion.DEFAULT.version))
    }
    if (publisher.name.isNotBlank()) {
        InsetGroup {
            InsetRow(stringResource(R.string.app_info_developer), detail = publisher.name)
            if (publisher.email.isNotBlank()) {
                InsetDivider()
                InsetRow(stringResource(R.string.app_info_contact), detail = publisher.email)
            }
        }
    }
    InsetGroup(caption = stringResource(R.string.app_info_legal)) {
        InsetRow(stringResource(R.string.app_info_privacy_policy),
            detail = stringResource(R.string.app_info_privacy_policy_detail), showChevron = true, onClick = onPrivacy)
        InsetDivider()
        InsetRow(stringResource(R.string.app_info_licenses),
            detail = stringResource(R.string.app_info_licenses_detail), showChevron = true, onClick = onLicenses)
    }
}

@Composable
private fun PrivacyPage(publisher: PublisherInfo) {
    val context = LocalContext.current
    val appName = stringResource(R.string.app_name)
    val blocks = remember(appName, publisher) {
        runCatching {
            LegalText.parse(PrivacyPolicy.render(context.readAsset(PrivacyPolicy.ASSET), appName, publisher.name, publisher.email))
        }.getOrNull()
    }
    if (blocks == null) {
        Text(stringResource(R.string.app_info_load_failed), style = MaterialTheme.typography.bodyMedium)
        return
    }
    for (block in blocks) {
        when (block) {
            is LegalBlock.Title -> Text(block.text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            is LegalBlock.Heading -> Text(block.text, Modifier.padding(top = 4.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
            is LegalBlock.Paragraph -> Text(withBold(block.text), style = MaterialTheme.typography.bodyMedium)
            is LegalBlock.Bullet -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("•", Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.bodyMedium)
                Text(withBold(block.text), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun LicensesPage(notices: OpenSourceNotices?, onOpen: (Int) -> Unit) {
    if (notices == null) {
        Text(stringResource(R.string.app_info_load_failed), style = MaterialTheme.typography.bodyMedium)
        return
    }
    InsetGroup {
        notices.components.forEachIndexed { index, component ->
            if (index > 0) InsetDivider()
            InsetRow(component.name, detail = "${component.version} · ${component.license}", showChevron = true,
                onClick = { onOpen(index) })
        }
    }
}

@Composable
private fun LicensePage(component: OpenSourceComponent) {
    val context = LocalContext.current
    val loadFailed = stringResource(R.string.app_info_load_failed)
    val texts = remember(component, loadFailed) {
        component.files.map { it to (runCatching { context.readAsset(OpenSourceNotices.ROOT + it) }.getOrNull() ?: loadFailed) }
    }
    Text(component.version, style = MaterialTheme.typography.bodyMedium)
    component.copyright?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    component.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    Text(component.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    for ((file, text) in texts) {
        Text(file.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall)
        Text(text.trimEnd(), style = MaterialTheme.typography.bodySmall)
    }
}

/** [text] with its `**bold**` runs in bold and the marks removed. */
private fun withBold(text: String): AnnotatedString = buildAnnotatedString {
    var rest = text
    while (true) {
        val start = rest.indexOf("**")
        val end = if (start >= 0) rest.indexOf("**", start + 2) else -1
        if (start < 0 || end < 0) { append(rest); break }
        append(rest.substring(0, start))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(rest.substring(start + 2, end)) }
        rest = rest.substring(end + 2)
    }
}

private fun Context.readAsset(path: String): String = assets.open(path).bufferedReader().use { it.readText() }
