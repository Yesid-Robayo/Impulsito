package io.github.yesidrobayo.shopledger.realaccounts

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusReport
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.rule.backup.BackupStatusResolver
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import org.junit.Assume.assumeTrue

/**
 * Lo común de las pruebas con las cuentas reales de prueba: corren dentro de la app `debug` que
 * la persona conectó a mano, con su destino, y nunca tocan la credencial ni las URI más que a
 * través del código de producción.
 *
 * - **Solo con `-e realAccounts true`.** Sin ese argumento del runner, cada prueba se salta: las
 *   suites normales nunca las corren, y Gradle tampoco, porque `connectedAndroidTest` desinstala
 *   la app al terminar y se llevaría el destino conectado.
 * - **El registro solo lleva conteos, duraciones, estados, códigos y resultados**, con la etiqueta
 *   [TAG] y en una línea `clave=valor`. Nunca la dirección del servicio, el usuario, las URI de
 *   las ranuras, nombres de archivos ni datos del negocio.
 * - **Datos inventados:** las ventas salen del negocio inventado que siembra `VolumeSeedTest`.
 */
internal object RealAccounts {
    /** La etiqueta de las líneas que lee el guion del PC. */
    const val TAG = "RealAccounts"

    /** El argumento del runner que habilita estas pruebas. */
    private const val ARGUMENT = "realAccounts"

    // Los nombres únicos de las subidas, repetidos de la capa de datos, donde son internos. Si allá
    // cambian, el sondeo de estas pruebas deja de ver los trabajos y lo dice en su línea.
    val UPLOAD_WORK_NAMES = listOf("backup-upload", "backup-upload-unmetered")
    const val DEBOUNCE_WORK_NAME = "backup-debounce"

    /** La autoridad de Drive, la única de la lista fija de nubes para las ranuras. */
    const val DRIVE_AUTHORITY = "com.google.android.apps.docs.storage"

    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val app: ShopLedgerApp
        get() = instrumentation.targetContext.applicationContext as ShopLedgerApp
    val context: Context get() = app

    /** Se salta la prueba si no se pidió con el argumento del runner. */
    fun requireRealAccounts() {
        assumeTrue(
            "Solo corre con -e $ARGUMENT true",
            InstrumentationRegistry.getArguments().getString(ARGUMENT) == "true",
        )
    }

    /** Un argumento de texto del runner, o [default]. */
    fun argument(name: String, default: String): String =
        InstrumentationRegistry.getArguments().getString(name) ?: default

    /** Los puertos de la base del negocio. Falla si la base no abre sin riesgo. */
    suspend fun ports(): DataPorts {
        val access = app.container.data.open()
        check(access is DataAccess.Ready) { "La base no abrió: ${access::class.simpleName}" }
        return access.ports
    }

    /**
     * Escribe una línea `clave=valor` para el guion. Solo acepta números, booleanos, enums y
     * textos de [Kind]: así nada del negocio ni de la cuenta llega al registro por descuido.
     */
    fun report(event: String, vararg fields: Pair<String, Any?>) {
        val text = fields.joinToString(" ") { (key, value) -> "$key=${safe(value)}" }
        Log.i(TAG, "event=$event $text")
    }

    private fun safe(value: Any?): String = when (value) {
        null -> "-"
        is Number, is Boolean -> value.toString()
        is Enum<*> -> value.name
        is Kind -> value.text
        else -> "?"
    }

    /** Un texto fijo, escrito en el código de estas pruebas, que sí puede ir al registro. */
    @JvmInline
    value class Kind(val text: String)

    /** El destino conectado, sin abrir la credencial: lo dice el estado guardado del respaldo. */
    suspend fun connectedKind(): BackupDestinationKind? =
        ports().backupStatus.current().destinationKind

    /** El estado del respaldo que vería la persona ahora. */
    suspend fun visibleStatus(): BackupStatusReport =
        BackupStatusResolver.resolve(ports().backupStatus.current(), app.container.clock.now())

    /** El *bucket* de la app, visto desde ella, o -1 antes de Android 9. */
    fun standbyBucket(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        context.getSystemService(UsageStatsManager::class.java).appStandbyBucket
    } else {
        -1
    }

    /** Los trabajos de subida que ve WorkManager, con su nombre único. */
    fun uploadWork(): List<Pair<String, WorkInfo>> {
        val manager = WorkManager.getInstance(context)
        return UPLOAD_WORK_NAMES.flatMap { name ->
            manager.getWorkInfosForUniqueWork(name).get().map { name to it }
        }
    }

    /** Los trabajos de la espera que junta cambios. */
    fun debounceWork(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(DEBOUNCE_WORK_NAME).get()

    /** Espera hasta [timeoutMs] a que [condition] se cumpla. Devuelve si se cumplió. */
    suspend fun awaitCondition(timeoutMs: Long, condition: suspend () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(POLL_MS)
        }
        return condition()
    }

    /**
     * Registra una venta inventada de una unidad del producto activo más antiguo, por el caso de
     * uso de la app, en una sola transacción. Necesita el negocio de `VolumeSeedTest`.
     */
    suspend fun registerInventedSale(ports: DataPorts) {
        val product = ports.products.observe(ArchiveFilter.ACTIVE).first().minByOrNull { it.id }
        checkNotNull(product) { "No hay productos: siembra antes el negocio inventado" }
        val method = ports.paymentMethods.findActive()
            .filter { it.details.kind != PaymentMethodKind.ON_ACCOUNT }
            .minByOrNull { it.sortOrder }
        checkNotNull(method) { "No hay un medio de pago activo" }
        val sell = RegisterSaleUseCase(
            transactions = ports.transactions,
            settings = ports.settings,
            products = ports.products,
            sales = ports.sales,
            payments = ports.salePayments,
            units = ports.units,
            systemRows = ports.systemRows,
            ledger = ports.stockLedger,
            methodNames = app.container.paymentMethodNames,
            taxRates = ports.taxRates,
            clock = app.container.clock,
            heldSales = ports.heldSales,
            productUnits = ports.productUnits,
        )
        val result = sell(
            SaleRequest(listOf(SaleLineRequest(product.id, QuantityMilli(ONE_UNIT))), method.id),
        )
        check(result is RegisterSaleResult.Registered) { "Venta rechazada" }
    }

    /**
     * Los valores de control del simulacro, como los anota la persona en su paso 1: cuántos
     * productos hay, la suma de su stock y de su valor, las ventas y la ganancia de toda la
     * historia, y una huella SHA-256 de todos los productos con sus saldos. Son cifras del negocio
     * inventado: se guardan en la carpeta privada de la app para comparar, y al registro solo va
     * si coinciden.
     */
    suspend fun controlValues(ports: DataPorts): String {
        val products =
            (
                ports.products.observe(ArchiveFilter.ACTIVE).first() +
                    ports.products.observe(ArchiveFilter.ARCHIVED).first()
                ).sortedBy { it.id }
        val digest = MessageDigest.getInstance("SHA-256")
        for (product in products) {
            val line = listOf(
                product.id,
                product.salePrice.minor,
                product.averageCost.minor,
                product.stockValue.minor,
                product.onHand.milli,
                product.archivedAt,
            ).joinToString("|")
            digest.update(line.toByteArray(Charsets.UTF_8))
        }
        val totals = ports.periodReports.observeTotals(WHOLE_HISTORY).first()
        val profit = totals.profit
        return listOf(
            "products=${products.size}",
            "onHand=${products.sumOf { it.onHand.milli }}",
            "stockValue=${products.sumOf { it.stockValue.minor }}",
            "sales=${totals.salesCount}",
            "salesNet=${profit.salesNetTotal.minor}",
            "cost=${profit.salesTotalCost.minor}",
            "expenses=${profit.recordedExpenses.minor}",
            "fingerprint=${digest.digest().joinToString("") { "%02x".format(it) }}",
        ).joinToString("\n")
    }

    /** La carpeta privada de estas pruebas, dentro de la app. Nunca sale del teléfono. */
    fun privateDir(): File = File(context.filesDir, "real-accounts").apply { mkdirs() }

    /** Toda la historia, para los totales de control. */
    private val WHOLE_HISTORY =
        ReportPeriod.Range(BusinessDate(LocalDate(2000, 1, 1)), BusinessDate(LocalDate(2100, 1, 1)))

    private const val POLL_MS = 500L
    private const val ONE_UNIT = 1_000L
}
