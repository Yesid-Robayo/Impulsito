package io.github.yesidrobayo.shopledger.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEvent
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEventType
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditResult
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseDestination
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseScope
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseSummary
import io.github.yesidrobayo.shopledger.domain.model.erase.StorageUsage
import io.github.yesidrobayo.shopledger.domain.model.experience.ToolGroup
import io.github.yesidrobayo.shopledger.domain.model.safety.CloudAccountCheck
import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyConsentStatus
import io.github.yesidrobayo.shopledger.ui.audit.AuditDay
import io.github.yesidrobayo.shopledger.ui.audit.AuditFilter
import io.github.yesidrobayo.shopledger.ui.audit.AuditLogScreen
import io.github.yesidrobayo.shopledger.ui.audit.AuditLogUiState
import io.github.yesidrobayo.shopledger.ui.experience.BusinessHubScreen
import io.github.yesidrobayo.shopledger.ui.experience.BusinessHubUiState
import io.github.yesidrobayo.shopledger.ui.experience.rememberHubTools
import io.github.yesidrobayo.shopledger.ui.help.HelpArticleId
import io.github.yesidrobayo.shopledger.ui.help.HelpGroup
import io.github.yesidrobayo.shopledger.ui.help.HelpList
import io.github.yesidrobayo.shopledger.ui.help.HelpListGroup
import io.github.yesidrobayo.shopledger.ui.help.HelpScreen
import io.github.yesidrobayo.shopledger.ui.help.HelpTopic
import io.github.yesidrobayo.shopledger.ui.help.HelpUiState
import io.github.yesidrobayo.shopledger.ui.privacy.CloudAccountScreen
import io.github.yesidrobayo.shopledger.ui.privacy.CloudAccountUiState
import io.github.yesidrobayo.shopledger.ui.privacy.DataTransparencyScreen
import io.github.yesidrobayo.shopledger.ui.privacy.DataTransparencyUiState
import io.github.yesidrobayo.shopledger.ui.privacy.Fact
import io.github.yesidrobayo.shopledger.ui.privacy.SecondFact
import io.github.yesidrobayo.shopledger.ui.storage.EraseFlow
import io.github.yesidrobayo.shopledger.ui.storage.StorageScreen
import io.github.yesidrobayo.shopledger.ui.storage.StorageUiState
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import kotlin.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las seudolocalizaciones de debug sobre las pantallas que llegaron después del núcleo: ayuda,
 * centro `Negocio`, transparencia, seguridad de la cuenta de la nube, auditoría, almacenamiento y
 * borrado. Como en [PseudoLocaleScreensTest], en `en-XA` todo recurso llega entre corchetes, así
 * que un texto de la interfaz sin ellos es texto fijo, y en `en-XA` y en `ar-XB` ningún texto se
 * sale de su caja. Lo que la escena trae como dato no cuenta. Datos inventados.
 *
 * Se corre con la app en cada seudolocalización (`cmd locale set-app-locales`): sin ella, se
 * salta.
 */
@RunWith(AndroidJUnit4::class)
class PseudoLocaleNewScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val audit = A11yAudit(compose)
    private var scene by mutableStateOf<(@Composable () -> Unit)?>(null)
    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val tag: String = resources.configuration.locales[0].toLanguageTag()

    @Test
    fun theNewScreensHaveNoFixedTextAndNoCuts() {
        assumeTrue("Con la app en $EXPANDED o $MIRRORED", tag == EXPANDED || tag == MIRRORED)
        compose.setContent { PreviewTheme { key(scene) { scene?.invoke() } } }
        val problems = mutableListOf<String>()
        val app = resources.getString(R.string.app_name)

        val titles = HelpArticleId.entries.associateWith { resources.getString(it.title, app) }
        val groups =
            HelpGroup.entries.map { group ->
                HelpListGroup(group, HelpArticleId.entries.filter { it.group == group })
            }
        val help =
            HelpUiState(
                list = HelpList.Browse(forScreen = HelpTopic.BACKUP.articles, groups = groups),
                topic = HelpTopic.BACKUP,
            )
        measure(problems, "ayuda") { HelpScreen(help, titles, onAction = {}) }

        val hub = BusinessHubUiState(inUse = emptySet(), expanded = ToolGroup.entries.toSet())
        measure(problems, "centro Negocio") {
            BusinessHubScreen(hub, rememberHubTools(), onAction = {}, onOpen = {}, onBack = {})
        }

        measure(problems, "transparencia") { DataTransparencyScreen(TRANSPARENCY, onAction = {}) }

        val account =
            CloudAccountUiState(
                loading = false,
                destination = Fact.Known(WEBDAV),
                checked = setOf(CloudAccountCheck.TWO_STEP),
            )
        measure(problems, "cuenta de la nube") { CloudAccountScreen(account, onAction = {}) }

        measure(problems, "auditoría") { AuditLogScreen(auditLog()) {} }

        val storage = StorageUiState(usage = USAGE, summary = SUMMARY, fromAndroid = true)
        measure(problems, "almacenamiento") { StorageScreen(storage) {} }
        val review = storage.copy(flow = EraseFlow.Review(EraseScope.DEVICE_AND_COPIES))
        measure(problems, "lo que se va a borrar") { StorageScreen(review) {} }

        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private fun measure(
        problems: MutableList<String>,
        name: String,
        content: @Composable () -> Unit,
    ) {
        scene = content
        problems += audit.problems().map { "$tag, $name: $it" }
        if (tag == EXPANDED) {
            if (texts().none { '[' in it }) problems += "$tag, $name: nada seudolocalizado"
            problems += fixedTexts().map { "$tag, $name: texto fijo \"$it\"" }
        }
    }

    /** Los textos con letras que no pasaron por la seudolocalización, salvo los datos. */
    private fun fixedTexts(): List<String> = texts()
        .filter { text ->
            PseudoLocaleScreensTest.LETTERS.containsMatchIn(text) &&
                '[' !in text &&
                DATA.none { it in text }
        }.distinct()

    private fun texts(): List<String> {
        compose.waitForIdle()
        val withText = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)
        return compose
            .onAllNodes(withText, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            }
    }

    private fun auditLog(): AuditLogUiState {
        val zone = checkNotNull(BusinessTimeZone.of("UTC"))
        val at = Instant.parse("2026-11-12T15:00:00Z")
        val events =
            listOf(
                AuditEvent(2, at, AuditEventType.EXPORT_CSV, AuditResult.SUCCESS),
                AuditEvent(1, at, AuditEventType.AUTH_FAILED, AuditResult.FAILURE),
            )
        val days = listOf(AuditDay(BusinessDate.of(at, zone), events))
        return AuditLogUiState.Ready(AuditFilter.ALL, days, zone, false)
    }

    private companion object {
        const val EXPANDED = PseudoLocaleScreensTest.EXPANDED
        const val MIRRORED = PseudoLocaleScreensTest.MIRRORED

        val WEBDAV = BackupDestinationInfo.WebDav(account = "negocio", host = "example.com")

        val TRANSPARENCY =
            DataTransparencyUiState(
                loading = false,
                consent = Fact.Known(PolicyConsentStatus.ACCEPTED),
                destination = Fact.Known(WEBDAV),
                encryptionOn = Fact.Known(false),
                second = Fact.Known(SecondFact.Folder("copias")),
                appLock = Fact.Known(true),
                updateCheck = Fact.Known(false),
            )

        val USAGE = StorageUsage(2_400_000, 38_100_000, 4_800_000, 600_000, 9_000_000_000)

        val SUMMARY =
            EraseSummary(
                records = 4_812,
                products = 120,
                photos = 38,
                unsyncedChanges = 3,
                photosPending = 2,
                destination = EraseDestination.WEBDAV,
                providerLabel = null,
                backupFailed = true,
                restoreInProgress = false,
                pending = null,
            )

        /**
         * Lo que la escena trae como dato y no como texto de la app: la cuenta, el servicio y la
         * carpeta de muestra, y lo que escriben Android e ICU con la región, como los tamaños de
         * archivo, las fechas y la zona.
         */
        val DATA =
            listOf(
                "negocio", "example.com", "copias", "kB", "MB", "GB", "UTC", "Nov", "Mon", "Tue",
                "Wed", "Thu", "Fri", "Sat", "Sun",
            )
    }
}
