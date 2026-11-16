package io.github.yesidrobayo.shopledger

import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.ui.experience.HUB_ENTRIES
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En el teléfono, en modo avión de verdad desde que se abre la app: cada pantalla del alcance
 * responde sin red, con la app entera y su base. Recorre las pestañas, `Vender`, `Compra` y
 * `Gasto` de Inicio, cada herramienta del centro `Negocio` (cuentas, clientes, documentos,
 * importar, reportes, inventario y el resto) y las filas de Ajustes que abren otra pantalla, ayuda
 * y actualizaciones incluidas.
 *
 * Una pantalla responde si, antes de [SETTLE_MILLIS], muestra algún texto que la anterior no
 * tenía y ya no le queda ningún monto cargando: su contenido, su estado vacío o su error, nunca
 * una espera. Ninguna pantalla puede decir que no hay conexión solo por abrirse: la red se usa al
 * tocar algo que la pide, como `Buscar actualización`. Junta todas las fallas y las muestra
 * juntas.
 *
 * No escribe datos del negocio: solo da por terminado el primer uso, como la prueba de la
 * apariencia. Registra solo el nombre de cada paso y cuánto tardó.
 */
@RunWith(AndroidJUnit4::class)
class OfflineWalkTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as ShopLedgerApp
    private val airplane = AirplaneMode(app)
    private val problems = mutableListOf<String>()
    private val labels = mutableMapOf<Int, String>()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun prepare() {
        // La prueba recorre Inicio y su barra: el primer uso, que se abre encima, ya terminó.
        runBlocking {
            val settings = (app.container.data.open() as DataAccess.Ready).ports.settings
            if (settings.get().onboardingCompletedAt == null) {
                settings.markOnboardingCompleted(app.container.clock.now())
            }
        }
        try {
            airplane.turnOn()
        } catch (failure: Throwable) {
            airplane.restore()
            throw failure
        }
    }

    @After
    fun clean() {
        scenario?.close()
        airplane.restore()
    }

    @Test
    fun everyScreenAnswersWithoutNetwork() {
        assertTrue(airplane.offline)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await("Inicio", ::atHome)

        // Las pestañas: Registros, Productos, Números y de vuelta Inicio.
        for (tab in TABS) visit(text(tab)) { tapText(text(tab)) }

        // `Vender`, `Compra` y `Gasto`, cada una desde Inicio y de vuelta.
        for (action in HOME_ACTIONS) {
            visit(text(action)) { tapText(text(action)) }
            backUntil(text(action), ::atHome)
        }

        // Cada herramienta del centro `Negocio`, por su buscador: sus grupos empiezan cerrados.
        val hub = text(R.string.hub_title)
        val openHub = { tapText(text(R.string.hub_entry_action)) }
        visit(hub, openHub)
        for (entry in HUB_ENTRIES) {
            val name = text(entry.name)
            if (!atHub(hub) && atHome()) openHub()
            if (!poll(OPEN_MILLIS) { atHub(hub) }) {
                problems += "$hub > $name: el centro no estaba a la vista"
                continue
            }
            // La búsqueda va antes de medir: sus resultados no son la pantalla nueva.
            val searched = runCatching {
                compose.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement(name)
                compose.waitForIdle()
            }
            if (searched.isFailure) {
                problems += "$hub > $name: no se pudo buscar en el centro"
                continue
            }
            // El centro carga primero qué herramientas están en uso: hasta entonces no hay filas.
            val row = hasText(name) and hasClickAction() and !hasSetTextAction()
            if (!poll(OPEN_MILLIS) { exists(row) }) {
                problems += "$hub > $name: la fila no apareció en el centro"
                continue
            }
            visit("$hub > $name") { compose.onAllNodes(row).onFirst().performClick() }
            backUntil(name) { atHub(hub) }
        }
        backUntil(hub, ::atHome)

        // Ajustes y cada fila que abre otra pantalla. Cifrado y auditoría dependen del bloqueo.
        val settings = text(R.string.settings_title)
        val gear = hasContentDescription(text(R.string.a11y_settings)) and hasClickAction()
        visit(settings) { compose.onAllNodes(gear).onFirst().performClick() }
        for (key in SETTINGS_ROWS) {
            val title = text(key)
            val row = hasText(title) and hasClickAction()
            val inSettings = { exists(hasText(settings)) && exists(row) }
            if (key in OPTIONAL_ROWS && !inSettings()) continue
            visit("$settings > $title") { tapText(title) }
            backUntil(title, inSettings)
        }

        assertTrue(problems.joinToString("\n"), problems.isEmpty())
        assertTrue("La red volvió durante el recorrido", airplane.offline)
    }

    /**
     * Abre [name] con [open] y espera a que responda sin red: un texto nuevo y ningún monto
     * cargando. Si no responde, lo anota y sigue con lo demás.
     */
    private fun visit(name: String, open: () -> Unit) {
        val before = texts()
        val started = SystemClock.elapsedRealtime()
        try {
            open()
        } catch (failure: Throwable) {
            problems += "$name: no se pudo abrir (${failure.javaClass.simpleName})"
            return
        }
        val answered = poll(SETTLE_MILLIS) { (texts() - before).isNotEmpty() && !loading() }
        val millis = SystemClock.elapsedRealtime() - started
        Log.i(TAG, "$name: ${if (answered) "respondió" else "sin respuesta"} en $millis ms")
        if (!answered) problems += "$name: no mostró su contenido en $SETTLE_MILLIS ms sin red"
        if (text(R.string.state_no_connection) in texts()) {
            problems += "$name: dijo que no hay conexión solo por abrirse"
        }
    }

    /**
     * Vuelve atrás, como el botón del sistema, hasta [arrived]: una hoja abierta encima pide un
     * toque más. Nunca vuelve desde Inicio, que cerraría la app.
     */
    private fun backUntil(from: String, arrived: () -> Boolean) {
        if (arrived()) return
        repeat(MAX_BACKS) {
            if (atHome()) {
                problems += "$from: atrás llegó a Inicio antes de volver a donde estaba"
                return
            }
            scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            if (poll(BACK_MILLIS, arrived)) return
        }
        problems += "$from: atrás no volvió a donde estaba"
    }

    private fun await(name: String, condition: () -> Boolean) {
        check(poll(OPEN_MILLIS, condition)) { "$name no abrió sin red" }
    }

    /** Toca el elemento con el texto [label], aunque esté más abajo en su lista. */
    private fun tapText(label: String) {
        val target = hasText(label) and hasClickAction() and !hasSetTextAction()
        if (!exists(target)) {
            val lists = compose.onAllNodes(hasScrollToNodeAction())
            for (index in lists.fetchSemanticsNodes().indices) {
                if (runCatching { lists[index].performScrollToNode(target) }.isSuccess) break
            }
        }
        val node = compose.onAllNodes(target).onFirst()
        // Fuera de una lista que se desplaza no hay nada que acercar.
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    /** Inicio, con `Vender` en la barra y sin un botón para volver o cerrar. */
    private fun atHome(): Boolean = exists(hasText(text(R.string.nav_sell)) and hasClickAction()) &&
        !exists(hasContentDescription(text(R.string.a11y_back))) &&
        !exists(hasContentDescription(text(R.string.a11y_close)))

    /** El centro `Negocio`, con su título y su buscador. */
    private fun atHub(title: String): Boolean = exists(hasText(title)) && exists(hasSetTextAction())

    private fun exists(matcher: SemanticsMatcher): Boolean = runCatching {
        compose
            .onAllNodes(matcher)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .isNotEmpty()
    }.getOrDefault(false)

    /** Cada texto a la vista, de todas las ventanas de la app. */
    private fun texts(): Set<String> = runCatching {
        compose
            .onAllNodes(HAS_TEXT, useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }
            .toSet()
    }.getOrDefault(emptySet())

    /** Algún monto o recuadro que todavía se está leyendo. */
    private fun loading(): Boolean = runCatching {
        compose
            .onAllNodes(
                hasContentDescription(text(R.string.a11y_loading)),
                useUnmergedTree = true,
            ).fetchSemanticsNodes(atLeastOneRootRequired = false)
            .isNotEmpty()
    }.getOrDefault(false)

    /**
     * Si [condition] se cumple antes de [timeoutMillis], mirando cada poco. Antes de mirar espera
     * a que la interfaz quede quieta, así una transición no deja dos pantallas a la vez.
     */
    private fun poll(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            runCatching { compose.waitForIdle() }
            if (condition()) return true
            Thread.sleep(POLL_MILLIS)
        }
        return condition()
    }

    /** El texto [id] en el idioma de la app, que puede no ser el del teléfono. */
    private fun text(@StringRes id: Int): String = labels.getOrPut(id) {
        var value = ""
        val current = checkNotNull(scenario) { "La app todavía no abrió" }
        current.onActivity { value = it.getString(id) }
        value
    }

    private companion object {
        const val TAG = "OfflineWalk"
        val HAS_TEXT = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)

        /**
         * Lo que puede tardar una pantalla en mostrar lo suyo. Los datos son locales: una
         * pantalla que no espera a la red tarda mucho menos.
         */
        const val SETTLE_MILLIS = 5_000L
        const val OPEN_MILLIS = 15_000L
        const val BACK_MILLIS = 2_000L
        const val POLL_MILLIS = 100L
        const val MAX_BACKS = 3

        val TABS =
            listOf(
                R.string.nav_records,
                R.string.nav_products,
                R.string.nav_numbers,
                R.string.nav_home,
            )
        val HOME_ACTIONS =
            listOf(
                R.string.nav_sell,
                R.string.home_shortcut_purchase,
                R.string.home_shortcut_expense,
            )

        val OPTIONAL_ROWS = setOf(R.string.encryption_title, R.string.audit_title)
        val SETTINGS_ROWS =
            listOf(
                R.string.region_title,
                R.string.language_title,
                R.string.tax_title,
                R.string.payment_method_title,
                R.string.receipt_template_title,
                R.string.printer_title,
                R.string.encryption_title,
                R.string.audit_title,
                R.string.account_title,
                R.string.import_title,
                R.string.offsite_title,
                R.string.storage_title,
                R.string.transparency_title,
                R.string.help_title,
                R.string.update_title,
            )
    }
}
