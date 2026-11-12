package io.github.yesidrobayo.shopledger.ui.sale

import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.MainActivity
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettingsInput
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import io.github.yesidrobayo.shopledger.format.TEST_CURRENCY
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cronometra la venta habitual en el teléfono, con la app entera y su base: desde Inicio, `Vender`,
 * el producto frecuente y `Registrar venta`, diez veces seguidas.
 *
 * Siembra veinte productos inventados con precio y stock, confirma una moneda de prueba y usa el
 * primer método de pago, el que la venta ofrece sin tocarlo. Antes de medir registra unas ventas
 * del producto frecuente por el caso de uso, para que encabece `Más vendidos` como en un negocio
 * con historial.
 *
 * Toca como una persona, con eventos del sistema, y no usa herramientas de interfaz que cambien el
 * reloj de las animaciones: lo medido es el tiempo real de la app, con sus transiciones. Busca cada
 * botón en el árbol de semántica de la pantalla y espera a que deje de moverse antes de tocarlo,
 * así que no cuenta el tiempo que una persona tarda en mirar y mover el dedo. Cada venta se da por
 * registrada cuando está en la base, y además se anota cuándo Inicio muestra la confirmación.
 *
 * Escribe ventas de verdad en la base de la app y fija su moneda, así que solo corre si se pide con
 * el argumento `habitualSaleTiming=true` del runner de instrumentación. Registra solo tiempos,
 * conteos y el estado del modo avión: ningún dato del negocio.
 */
@RunWith(AndroidJUnit4::class)
class HabitualSaleTimingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as ShopLedgerApp
    private var taps = 0

    @Test
    fun aUsualSaleFromHomeTakesThreeTaps() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString(ARGUMENT) == "true")
        val ports = (app.container.data.open() as DataAccess.Ready).ports
        val clock = app.container.clock
        val catalog = seed(ports, clock)
        var lastSaleId = catalog.lastSaleId
        val airplane =
            Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

        val runs = mutableListOf<SaleTiming>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var labels: Labels
            lateinit var screen: View
            scenario.onActivity {
                labels = Labels.of(it)
                screen = it.window.decorView
            }
            // Inicio listo: la barra con `Vender`, quieta.
            awaitReady(screen) { sellButton(it, labels) }

            repeat(SALES) { index ->
                // Cada venta empieza sin la confirmación de la anterior, que dura lo que dura
                // `Deshacer`: abajo se ve qué pasa si no se espera.
                awaitGone(screen) { confirmation(it, labels) }
                val run = sellOnce(screen, labels, catalog.frequentName, ports, lastSaleId + 1)
                assertSold(ports, lastSaleId + 1, catalog.frequentId)
                lastSaleId++
                runs += run
                Log.i(
                    TAG,
                    "venta ${index + 1}: ${run.taps} toques, ${run.savedMillis} ms hasta la base " +
                        "(${run.openMillis} ms en abrir, ${run.addMillis} ms en agregar), " +
                        "${run.homeMillis} ms hasta Inicio",
                )
            }
            val saved = runs.map { it.savedMillis }
            val home = runs.map { it.homeMillis }
            Log.i(
                TAG,
                "resumen: ${runs.size} ventas, modo avión " +
                    "${if (airplane) "encendido" else "apagado"}, " +
                    "hasta la base mediana ${median(saved)} ms y máximo ${saved.max()} ms, " +
                    "hasta Inicio mediana ${median(home)} ms y máximo ${home.max()} ms",
            )

            // Otra venta enseguida, sin esperar a que se vaya la confirmación: se anota si esa
            // confirmación tapa `Registrar venta`. Se mide la caja de su texto, que es menor que el
            // mensaje entero. No se toca, para no pulsar `Deshacer`.
            awaitGone(screen) { confirmation(it, labels) }
            sellOnce(screen, labels, catalog.frequentName, ports, lastSaleId + 1)
            tap(awaitReady(screen) { sellButton(it, labels) })
            tap(awaitReady(screen) { productButton(it, labels, PRODUCT_NAMES[1]) })
            val register = awaitReady(screen) { registerButton(it, labels) }
            val message = look(screen) { snackbar(it, labels) }
            val covered = message?.bounds?.intersect(register.bounds)
            val coveredPx = covered?.takeIf { it.height > 0f && it.width > 0f }?.height ?: 0f
            val seen = if (message == null) "ausente" else "visible"
            val height = register.bounds.height.toInt()
            Log.i(
                TAG,
                "venta enseguida: confirmación anterior $seen, y su texto solo ya tapa " +
                    "${coveredPx.toInt()} de $height px de alto de Registrar venta",
            )
        }

        assertEquals(SALES, runs.size)
        assertTrue("Una venta pidió más de tres toques", runs.all { it.taps <= MAX_TAPS })
        // El tope de diez segundos incluye a la persona: la app sola queda muy por debajo.
        val slowest = runs.maxOf { it.homeMillis }
        assertTrue("La app sola tardó $slowest ms", slowest < MAX_MILLIS)
    }

    /**
     * Una venta desde Inicio: `Vender`, [productName] y `Registrar venta`. Termina cuando la venta
     * [saleId] está en la base e Inicio muestra su confirmación.
     */
    private suspend fun sellOnce(
        screen: View,
        labels: Labels,
        productName: String,
        ports: DataPorts,
        saleId: Long,
    ): SaleTiming {
        val before = taps
        val start = SystemClock.elapsedRealtimeNanos()
        tap(awaitReady(screen) { sellButton(it, labels) })
        val product = awaitReady(screen) { productButton(it, labels, productName) }
        val opened = SystemClock.elapsedRealtimeNanos()
        tap(product)
        val register = awaitReady(screen) { registerButton(it, labels) }
        val added = SystemClock.elapsedRealtimeNanos()
        tap(register)
        awaitSale(ports, saleId)
        val saved = SystemClock.elapsedRealtimeNanos()
        awaitReady(screen, QUEUE_WAIT_MILLIS) { confirmation(it, labels) }
        val home = SystemClock.elapsedRealtimeNanos()
        return SaleTiming(
            taps = taps - before,
            openMillis = millis(start, opened),
            addMillis = millis(opened, added),
            savedMillis = millis(start, saved),
            homeMillis = millis(start, home),
        )
    }

    /** La venta [saleId] tiene una sola línea, de una unidad de [productId]. */
    private suspend fun assertSold(ports: DataPorts, saleId: Long, productId: Long) {
        val lines = ports.sales.findLines(saleId)
        assertEquals(1, lines.size)
        assertEquals(productId, lines.single().content.productId)
        assertEquals(QuantityMilli(ONE_UNIT), lines.single().content.quantity)
    }

    private class Catalog(val frequentId: Long, val frequentName: String, val lastSaleId: Long)

    private class SaleTiming(
        val taps: Int,
        val openMillis: Long,
        val addMillis: Long,
        val savedMillis: Long,
        val homeMillis: Long,
    )

    /** Los textos de la app en el idioma con que se abrió, para encontrar sus botones. */
    private class Labels(
        val sell: String,
        val register: String,
        val registered: String,
        val saleTitle: String,
        val recentSales: String,
    ) {
        companion object {
            fun of(activity: MainActivity): Labels = Labels(
                sell = activity.getString(R.string.nav_sell),
                register = activity.getString(R.string.sale_register),
                saleTitle = activity.getString(R.string.sale_new_title),
                recentSales = activity.getString(R.string.home_recent_sales),
                // Lo que va antes del monto en `Venta registrada: {monto}`.
                registered = activity.getString(R.string.confirm_sale_registered, MARK)
                    .substringBefore(MARK),
            )
        }
    }

    /**
     * Moneda de prueba confirmada, primer uso terminado, veinte productos y unas ventas previas del
     * producto frecuente. Si una corrida anterior ya los creó, los reutiliza.
     */
    private suspend fun seed(ports: DataPorts, clock: Clock): Catalog {
        val settings = ports.settings
        val regional = settings.get().regional
        if (!regional.currency.code.isConfirmed) {
            val confirmed =
                SaveRegionalSettingsUseCase(ports.transactions, settings, clock)(
                    RegionalSettingsInput(
                        currencyCode = TEST_CURRENCY,
                        currencyDecimals = TEST_DECIMALS,
                        currencySymbol = null,
                        cashRounding = Money.ZERO,
                        formatLocaleTag = regional.formatLocaleTag,
                        businessTimeZoneId = regional.businessTimeZoneId,
                        weekStartDay = regional.weekStartDay,
                        csvSeparator = regional.csvSeparator,
                    ),
                )
            assertEquals(SaveRegionalSettingsResult.Saved, confirmed)
        }
        if (settings.get().onboardingCompletedAt == null) {
            settings.markOnboardingCompleted(clock.now())
        }

        val calendar = BusinessCalendarSource(settings)
        val create = CreateProductUseCase(
            ports.transactions, ports.products, ports.categories, ports.suppliers,
            ports.units, ports.systemRows, ports.priceHistory, ports.stockLedger,
            ports.lowStockAlerts, calendar, CurrencyLock(settings), clock,
        )
        val ids = PRODUCT_NAMES.mapIndexed { index, name ->
            val form = ProductForm(
                name = name,
                salePrice = Money(BASE_PRICE + index * PRICE_STEP),
                cost = Money(BASE_COST + index * PRICE_STEP),
            )
            val result = create(form, QuantityMilli(INITIAL_STOCK))
            if (result is SaveProductResult.Saved) {
                result.productId
            } else {
                // Una corrida anterior ya lo creó: se reutiliza.
                check(result is SaveProductResult.DuplicateName) {
                    "No se pudo crear un producto de prueba: $result"
                }
                requireNotNull(
                    ports.products.findActiveByNormalizedName(TextNormalizer.normalize(name)),
                ) { "El producto repetido no aparece" }.id
            }
        }

        val methods = ports.paymentMethods.findActive()
        val method = requireNotNull(methods.minByOrNull { it.sortOrder }) {
            "La base no trae ningún método de pago activo"
        }
        val register = RegisterSaleUseCase(
            transactions = ports.transactions,
            settings = settings,
            products = ports.products,
            sales = ports.sales,
            payments = ports.salePayments,
            units = ports.units,
            systemRows = ports.systemRows,
            ledger = ports.stockLedger,
            methodNames = app.container.paymentMethodNames,
            taxRates = ports.taxRates,
            clock = clock,
            heldSales = ports.heldSales,
            productUnits = ports.productUnits,
        )
        val frequentId = ids[FREQUENT_INDEX]
        // Historial: el frecuente varias veces y otros dos una vez, como en un día cualquiera.
        val history = List(HISTORY_SALES) { frequentId } + listOf(ids[1], ids[2])
        var lastSaleId = 0L
        for (productId in history) {
            val result = register(
                SaleRequest(
                    lines = listOf(SaleLineRequest(productId, QuantityMilli(ONE_UNIT))),
                    paymentMethodId = method.id,
                ),
            )
            lastSaleId = (result as? RegisterSaleResult.Registered)?.saleId
                ?: error("No se pudo registrar el historial: $result")
        }
        return Catalog(frequentId, PRODUCT_NAMES[FREQUENT_INDEX], lastSaleId)
    }

    // Botones de la pantalla, buscados en el árbol de semántica combinado.

    private fun sellButton(root: SemanticsNode, labels: Labels): SemanticsNode? =
        clickables(root) { it.texts().any { text -> text == labels.sell } }
            .maxByOrNull { it.boundsInRoot.bottom }

    /**
     * El producto [name] en la venta, cuando Inicio ya salió del todo: sus ventas recientes también
     * nombran productos, y tocar una abre su detalle.
     */
    private fun productButton(root: SemanticsNode, labels: Labels, name: String): SemanticsNode? {
        val nodes = all(root)
        val saleOnly = nodes.any { it.hasText(labels.saleTitle) } &&
            nodes.none { it.hasText(labels.recentSales) }
        if (!saleOnly) return null
        return nodes.firstOrNull { node ->
            node.config.contains(SemanticsActions.OnClick) && node.hasText(name)
        }
    }

    private fun registerButton(root: SemanticsNode, labels: Labels): SemanticsNode? =
        clickables(root) { node ->
            !node.isDisabled() && node.texts().any { it.startsWith(labels.register) }
        }.firstOrNull()

    /** La confirmación de la venta, con Inicio a la vista y la venta ya cerrada. */
    private fun confirmation(root: SemanticsNode, labels: Labels): SemanticsNode? {
        if (all(root).any { it.hasText(labels.saleTitle) }) return null
        return snackbar(root, labels)
    }

    /** El mensaje `Venta registrada`, sobre la pantalla que sea. */
    private fun snackbar(root: SemanticsNode, labels: Labels): SemanticsNode? =
        all(root).firstOrNull { node -> node.texts().any { it.startsWith(labels.registered) } }

    private fun clickables(
        root: SemanticsNode,
        match: (SemanticsNode) -> Boolean,
    ): List<SemanticsNode> = all(root).filter {
        it.config.contains(SemanticsActions.OnClick) && match(it)
    }

    private fun all(root: SemanticsNode): List<SemanticsNode> {
        val found = mutableListOf<SemanticsNode>()
        val pending = ArrayDeque(listOf(root))
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            found += node
            pending.addAll(node.children)
        }
        return found
    }

    private fun SemanticsNode.texts(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private fun SemanticsNode.hasText(text: String): Boolean = texts().any { it == text }

    private fun SemanticsNode.isDisabled(): Boolean =
        config.getOrNull(SemanticsProperties.Disabled) != null

    /** Dónde tocar, en píxeles de la pantalla. */
    private class Target(val x: Float, val y: Float, val bounds: Rect)

    /**
     * Espera a que [find] encuentre el botón y que no se haya movido desde la mirada anterior, un
     * cuadro antes: así no se toca a mitad de una transición. Devuelve dónde tocarlo.
     */
    private fun awaitReady(
        screen: View,
        timeoutMillis: Long = WAIT_MILLIS,
        find: (SemanticsNode) -> SemanticsNode?,
    ): Target {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var previous: Rect? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val target = look(screen, find)
            if (target != null && target.bounds == previous) return target
            previous = target?.bounds
            SystemClock.sleep(FRAME_MILLIS)
        }
        error("El botón no apareció en $timeoutMillis ms")
    }

    /** Espera a que [find] ya no encuentre nada en la pantalla. */
    private fun awaitGone(screen: View, find: (SemanticsNode) -> SemanticsNode?) {
        val deadline = SystemClock.elapsedRealtime() + QUEUE_WAIT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (look(screen, find) == null) return
            SystemClock.sleep(GONE_POLL_MILLIS)
        }
        error("El mensaje no se fue en $QUEUE_WAIT_MILLIS ms")
    }

    private fun look(screen: View, find: (SemanticsNode) -> SemanticsNode?): Target? {
        var target: Target? = null
        // En el hilo principal, sin esperar a que se calme: la app sigue con sus animaciones.
        instrumentation.runOnMainSync {
            val root = composeRootOf(screen) ?: return@runOnMainSync
            val node = find(root.semanticsOwner.rootSemanticsNode) ?: return@runOnMainSync
            val origin = IntArray(2)
            root.view.getLocationOnScreen(origin)
            val bounds = node.boundsInRoot
            target = Target(
                x = origin[0] + bounds.center.x,
                y = origin[1] + bounds.center.y,
                bounds = bounds,
            )
        }
        return target
    }

    private fun composeRootOf(view: View): ViewRootForTest? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                composeRootOf(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun tap(target: Target) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(
                down,
                SystemClock.uptimeMillis(),
                action,
                target.x,
                target.y,
                0,
            )
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        taps++
    }

    private suspend fun awaitSale(ports: DataPorts, saleId: Long) {
        val deadline = SystemClock.elapsedRealtime() + WAIT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ports.sales.findById(saleId) != null) return
            SystemClock.sleep(DATABASE_POLL_MILLIS)
        }
        error("La venta no llegó a la base en $WAIT_MILLIS ms")
    }

    private fun millis(from: Long, to: Long): Long = (to - from) / NANOS_PER_MILLI

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2
        } else {
            sorted[middle]
        }
    }

    private companion object {
        const val ARGUMENT = "habitualSaleTiming"
        const val TAG = "HabitualSaleTiming"
        const val MARK = "\u0000"
        const val SALES = 10
        const val MAX_TAPS = 3
        const val MAX_MILLIS = 10_000L
        const val WAIT_MILLIS = 10_000L
        const val QUEUE_WAIT_MILLIS = 25_000L
        const val GONE_POLL_MILLIS = 100L
        const val FRAME_MILLIS = 16L
        const val DATABASE_POLL_MILLIS = 4L
        const val NANOS_PER_MILLI = 1_000_000L
        const val ONE_UNIT = 1_000L
        const val INITIAL_STOCK = 500_000L
        const val TEST_DECIMALS = 2
        const val BASE_PRICE = 1_500L
        const val BASE_COST = 900L
        const val PRICE_STEP = 250L
        const val HISTORY_SALES = 3
        const val FREQUENT_INDEX = 12

        // Veinte productos inventados. Ningún nombre contiene a otro, para encontrarlos sin dudas.
        val PRODUCT_NAMES = listOf(
            "Arroz blanco", "Azúcar morena", "Café molido", "Galletas de avena", "Harina de maíz",
            "Jabón de barra", "Leche entera", "Lentejas", "Mantequilla", "Mermelada de mora",
            "Panela", "Pasta corta", "Queso fresco", "Sal marina", "Salsa de tomate",
            "Té verde", "Vinagre", "Yogur natural", "Fósforos", "Velas blancas",
        )
    }
}
