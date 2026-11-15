package io.github.yesidrobayo.shopledger.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
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
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseStop
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseSummary
import io.github.yesidrobayo.shopledger.domain.model.erase.StorageUsage
import io.github.yesidrobayo.shopledger.domain.model.experience.ToolGroup
import io.github.yesidrobayo.shopledger.domain.model.safety.CloudAccountCheck
import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyConsentStatus
import io.github.yesidrobayo.shopledger.ui.audit.AuditDay
import io.github.yesidrobayo.shopledger.ui.audit.AuditFilter
import io.github.yesidrobayo.shopledger.ui.audit.AuditLogScreen
import io.github.yesidrobayo.shopledger.ui.audit.AuditLogUiState
import io.github.yesidrobayo.shopledger.ui.components.DemoFrame
import io.github.yesidrobayo.shopledger.ui.components.LocalDemoMode
import io.github.yesidrobayo.shopledger.ui.experience.BusinessHubScreen
import io.github.yesidrobayo.shopledger.ui.experience.BusinessHubUiState
import io.github.yesidrobayo.shopledger.ui.experience.rememberHubTools
import io.github.yesidrobayo.shopledger.ui.help.ArticleContent
import io.github.yesidrobayo.shopledger.ui.help.HelpArticleId
import io.github.yesidrobayo.shopledger.ui.help.HelpGroup
import io.github.yesidrobayo.shopledger.ui.help.HelpList
import io.github.yesidrobayo.shopledger.ui.help.HelpListGroup
import io.github.yesidrobayo.shopledger.ui.help.HelpMarkdown
import io.github.yesidrobayo.shopledger.ui.help.HelpScreen
import io.github.yesidrobayo.shopledger.ui.help.HelpTopic
import io.github.yesidrobayo.shopledger.ui.help.HelpUiState
import io.github.yesidrobayo.shopledger.ui.help.OpenArticle
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las pantallas nuevas de la ayuda, la privacidad, el borrado, la auditoría y el centro `Negocio`,
 * y la banda de la demostración, medidas en el teléfono con la letra que tenga: se corre una vez
 * con 1.0 y otra con 2.0 en `font_scale`, como `AccessibilityFlowsTest`, para que Android aplique
 * su escala no lineal.
 *
 * Cada prueba recorre las escenas de una pantalla (con datos, vacía, con error, cargando, hojas
 * del borrado y demostración) y junta las fallas de [A11yAudit]: todo lo tocable mide 48 dp y
 * tiene nombre, y ningún texto se sale de su caja. Falla con la lista entera. Además comprueba lo
 * que TalkBack anuncia y no depende del tamaño: cuántos resultados dejó una búsqueda, la fila de
 * la demostración en `Ahora mismo` y la ayuda de la cuenta de la nube. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class ExperienceScreensAccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    private val audit = A11yAudit(compose)
    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val app = resources.getString(R.string.app_name)
    private var scene by mutableStateOf<(@Composable () -> Unit)?>(null)

    /** La escala de la letra del teléfono, en por ciento. */
    private val percent: Int
        get() = (resources.configuration.fontScale * PERCENT).toInt()

    /** Dibuja una sola vez; cada escena reemplaza lo que se ve. */
    private fun start() {
        compose.setContent { PreviewTheme { key(scene) { scene?.invoke() } } }
    }

    /** Mide [content], con los datos de ejemplo si [demo], y junta sus fallas. */
    private fun measure(
        problems: MutableList<String>,
        name: String,
        demo: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        scene = { CompositionLocalProvider(LocalDemoMode provides demo) { content() } }
        problems += audit.problems().map { "$name al $percent %: $it" }
    }

    private fun assertClean(problems: List<String>) {
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private fun assertShown(text: String) {
        assertTrue(text, compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun helpPasses() {
        start()
        val problems = mutableListOf<String>()
        val titles = HelpArticleId.entries.associateWith { resources.getString(it.title, app) }
        val browse =
            HelpList.Browse(
                forScreen = HelpTopic.BACKUP.articles,
                groups =
                    HelpGroup.entries.map { group ->
                        HelpListGroup(group, HelpArticleId.entries.filter { it.group == group })
                    },
            )
        val found = listOf(HelpArticleId.BACKUP_LIMITS, HelpArticleId.RESTORE)
        val scenes =
            listOf(
                "ayuda" to HelpUiState(list = browse, topic = HelpTopic.BACKUP),
                "ayuda con búsqueda" to
                    HelpUiState(query = "copia", list = HelpList.Search(found)),
                "ayuda sin resultados" to
                    HelpUiState(query = "zzz", list = HelpList.Search(emptyList())),
                "artículo" to
                    HelpUiState(
                        standalone = true,
                        article =
                            OpenArticle(
                                HelpArticleId.BACKUP_LIMITS,
                                ArticleContent.Ready(HelpMarkdown.parse(ARTICLE, app)),
                            ),
                    ),
                "artículo que no abre" to
                    HelpUiState(
                        standalone = true,
                        article = OpenArticle(null, ArticleContent.Failed),
                    ),
            )
        for ((name, state) in scenes) {
            measure(problems, name) { HelpScreen(state, titles, onAction = {}) }
        }
        assertClean(problems)

        // Sin resultados también se dice: TalkBack lo lee al cambiar, sin mover el foco.
        scene = { HelpScreen(scenes[2].second, titles, onAction = {}) }
        assertShown(resources.getQuantityString(R.plurals.a11y_help_search_results, 0, 0))
    }

    @Test
    fun storageAndErasePass() {
        start()
        val problems = mutableListOf<String>()
        val usage = StorageUsage(2_400_000, 38_100_000, 4_800_000, 600_000, 9_000_000_000)
        val ready = StorageUiState(usage = usage, summary = SUMMARY, fromAndroid = true)
        val scenes =
            listOf(
                "almacenamiento" to ready,
                "almacenamiento midiendo" to StorageUiState(),
                "qué se borra" to ready.copy(flow = EraseFlow.Scope(null)),
                "solo este teléfono" to ready.copy(flow = EraseFlow.Scope(EraseScope.DEVICE_ONLY)),
                "lo que se va a borrar" to
                    ready.copy(flow = EraseFlow.Review(EraseScope.DEVICE_AND_COPIES)),
                "escribir la palabra" to
                    ready.copy(flow = EraseFlow.Confirm(EraseScope.DEVICE_AND_COPIES, "")),
                "borrando" to ready.copy(flow = EraseFlow.Running(cloud = true)),
                "sin conexión" to ready.copy(flow = EraseFlow.Stopped(EraseStop.OFFLINE)),
                "la nube no dejó" to ready.copy(flow = EraseFlow.Stopped(EraseStop.REFUSED)),
                "confirmar solo el teléfono" to ready.copy(flow = EraseFlow.ConfirmDeviceOnly),
            )
        for ((name, state) in scenes) {
            measure(problems, name) { StorageScreen(state) {} }
        }
        assertClean(problems)
    }

    @Test
    fun dataTransparencyPasses() {
        start()
        val problems = mutableListOf<String>()
        val ready =
            DataTransparencyUiState(
                loading = false,
                consent = Fact.Known(PolicyConsentStatus.ACCEPTED),
                destination = Fact.Known(WEBDAV),
                encryptionOn = Fact.Known(false),
                second = Fact.Known(SecondFact.Folder(LONG_NAME)),
                appLock = Fact.Known(true),
                updateCheck = Fact.Known(false),
            )
        val scenes =
            listOf(
                "transparencia" to ready,
                "transparencia con la nube del teléfono" to
                    ready.copy(destination = Fact.Known(SLOTS), encryptionOn = Fact.Known(true)),
                "política por aceptar" to
                    ready.copy(consent = Fact.Known(PolicyConsentStatus.OUTDATED)),
                "datos sin leer" to DataTransparencyUiState(loading = false),
                "transparencia cargando" to DataTransparencyUiState(),
            )
        for ((name, state) in scenes) {
            measure(problems, name) { DataTransparencyScreen(state, onAction = {}) }
        }
        measure(problems, "transparencia con datos de ejemplo", demo = true) {
            DataTransparencyScreen(ready, onAction = {})
        }
        assertClean(problems)

        // Con la demostración, `Ahora mismo` dice que nada se respalda.
        assertShown(resources.getString(R.string.transparency_now_demo))
    }

    @Test
    fun auditLogPasses() {
        start()
        val problems = mutableListOf<String>()
        val zone = checkNotNull(BusinessTimeZone.of("UTC"))
        val at = Instant.parse("2026-11-12T15:00:00Z")
        val events =
            listOf(
                AuditEvent(3, at, AuditEventType.AUTH_FAILED, AuditResult.FAILURE),
                AuditEvent(2, at, AuditEventType.EXPORT_CSV, AuditResult.SUCCESS),
                AuditEvent(1, at, AuditEventType.AUTH_FAILED, AuditResult.SUCCESS),
            )
        val day = listOf(AuditDay(BusinessDate.of(at, zone), events))
        val scenes =
            listOf(
                "auditoría" to AuditLogUiState.Ready(AuditFilter.ALL, day, zone, false),
                "auditoría vacía" to
                    AuditLogUiState.Ready(AuditFilter.ALL, emptyList(), zone, false),
                "sin fallos" to
                    AuditLogUiState.Ready(AuditFilter.FAILURES, emptyList(), zone, false),
                "auditoría sin leer" to AuditLogUiState.ReadFailed,
                "auditoría cargando" to AuditLogUiState.Loading,
            )
        for ((name, state) in scenes) {
            measure(problems, name) { AuditLogScreen(state) {} }
        }
        assertClean(problems)
    }

    @Test
    fun cloudAccountPasses() {
        start()
        val problems = mutableListOf<String>()
        val scenes =
            listOf(
                "cuenta con WebDAV" to
                    CloudAccountUiState(
                        loading = false,
                        destination = Fact.Known(WEBDAV),
                        checked = setOf(CloudAccountCheck.TWO_STEP),
                    ),
                "cuenta con la nube del teléfono" to
                    CloudAccountUiState(
                        loading = false,
                        destination = Fact.Known(SLOTS),
                        checked = CloudAccountCheck.entries.toSet(),
                    ),
                "sin cuenta" to
                    CloudAccountUiState(loading = false, destination = Fact.Known(null)),
                "acceso perdido" to
                    CloudAccountUiState(
                        loading = false,
                        destination = Fact.Known(WEBDAV),
                        accessLost = true,
                    ),
                "cuenta sin leer" to CloudAccountUiState(loading = false),
                "cuenta cargando" to CloudAccountUiState(),
            )
        for ((name, state) in scenes) {
            measure(problems, name) { CloudAccountScreen(state, onAction = {}) }
        }
        assertClean(problems)

        // La barra lleva la ayuda de la cuenta, con el título de su artículo.
        val topic = resources.getString(HelpArticleId.CLOUD_ACCOUNT.title, app)
        val help = resources.getString(R.string.a11y_help_link, topic)
        assertTrue(
            help,
            compose.onAllNodesWithContentDescription(help).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun businessHubPasses() {
        start()
        val problems = mutableListOf<String>()
        val scenes =
            listOf(
                "centro Negocio" to BusinessHubUiState(inUse = emptySet()),
                "centro Negocio abierto" to
                    BusinessHubUiState(inUse = emptySet(), expanded = ToolGroup.entries.toSet()),
                "centro Negocio sin resultados" to
                    BusinessHubUiState(inUse = emptySet(), query = "zzz"),
            )
        for ((name, state) in scenes) {
            measure(problems, name) {
                BusinessHubScreen(
                    state,
                    rememberHubTools(),
                    onAction = {},
                    onOpen = {},
                    onBack = {},
                )
            }
        }
        assertClean(problems)

        // La última escena es la búsqueda sin resultados: el conteo se dice igual.
        assertShown(resources.getQuantityString(R.plurals.a11y_hub_search_results, 0, 0))
    }

    @Test
    fun theDemoBannerPasses() {
        start()
        val problems = mutableListOf<String>()
        measure(problems, "banda de datos de ejemplo", demo = true) {
            DemoFrame(active = true, onExit = {}) {
                Box(Modifier.fillMaxSize()) { Text(LONG_NAME) }
            }
        }
        measure(problems, "banda saliendo", demo = true) {
            DemoFrame(active = true, onExit = {}, exiting = true) {
                Box(Modifier.fillMaxSize()) { Text(LONG_NAME) }
            }
        }
        assertClean(problems)
    }

    private companion object {
        const val PERCENT = 100

        /** Un artículo sin enlaces: un enlace dentro del texto no es un objetivo de 48 dp. */
        const val ARTICLE =
            "Las copias salen solas cuando hay cambios.\n\n## Lo que no cubre\n\n" +
                "- Un teléfono perdido sin copia.\n- Una cuenta de la nube sin acceso."

        const val LONG_NAME =
            "Carpeta de copias del negocio inventado con un nombre largo de ochenta caracteres"

        val WEBDAV = BackupDestinationInfo.WebDav(account = "negocio", host = "example.com")
        val SLOTS = BackupDestinationInfo.Slots(providerLabel = "Drive")
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
    }
}
