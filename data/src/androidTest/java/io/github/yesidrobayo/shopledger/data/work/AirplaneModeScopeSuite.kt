package io.github.yesidrobayo.shopledger.data.work

import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.backup.BackupDrillTest
import io.github.yesidrobayo.shopledger.data.demo.DemoSessionOnDatabaseTest
import io.github.yesidrobayo.shopledger.data.export.CsvExportOnDatabaseTest
import io.github.yesidrobayo.shopledger.data.export.SummaryPackageOnDatabaseTest
import io.github.yesidrobayo.shopledger.data.imports.ImportCorpusTest
import io.github.yesidrobayo.shopledger.data.repository.AdvancedCatalogDrillTest
import io.github.yesidrobayo.shopledger.data.repository.AdvancedStockDrillTest
import io.github.yesidrobayo.shopledger.data.repository.CountsOnDatabaseTest
import io.github.yesidrobayo.shopledger.data.repository.CreditAndCashDrillTest
import io.github.yesidrobayo.shopledger.data.repository.DocumentsDrillTest
import io.github.yesidrobayo.shopledger.data.repository.ExpenseOnDatabaseTest
import io.github.yesidrobayo.shopledger.data.repository.PrivacyDrillTest
import io.github.yesidrobayo.shopledger.data.repository.PurchaseOnDatabaseTest
import io.github.yesidrobayo.shopledger.data.repository.RegisterSaleOnDatabaseTest
import io.github.yesidrobayo.shopledger.data.repository.RegisterSplitSaleOnDatabaseTest
import io.github.yesidrobayo.shopledger.data.repository.ReportsDrillTest
import io.github.yesidrobayo.shopledger.data.repository.ReturnsAndPricesDrillTest
import org.junit.Assume.assumeTrue
import org.junit.ClassRule
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.junit.runners.Suite
import org.junit.runners.Suite.SuiteClasses

/**
 * Todo el alcance de la capa de datos en modo avión de verdad, con la radio apagada y ninguna red
 * activa, desde el principio hasta el final de la tanda: vender, cobrar con varios pagos, comprar,
 * registrar un gasto, contar el inventario, los reportes, los documentos, importar y exportar, la
 * demostración, la privacidad y el respaldo con sus destinos falsos.
 *
 * Cada clase es la misma que corre en la suite de siempre, sin cambiar nada: lo que se prueba aquí
 * es que ninguna necesita la red. Los servidores falsos de esas pruebas viven en el propio
 * teléfono, y la dirección local sigue respondiendo en modo avión. Lo que sí espera a la red, la
 * copia remota, lo prueba aparte [RemoteCopyWaitsOfflineTest].
 *
 * Repite pruebas largas que la suite ya corre con red, así que solo corre si se pide con el
 * argumento `airplaneScope=true` del runner de instrumentación:
 *
 * ```
 * adb shell am instrument -w -e airplaneScope true \
 *   -e class io.github.yesidrobayo.shopledger.data.work.AirplaneModeScopeSuite <paquete>/<runner>
 * ```
 */
@RunWith(Suite::class)
@SuiteClasses(
    RegisterSaleOnDatabaseTest::class,
    RegisterSplitSaleOnDatabaseTest::class,
    PurchaseOnDatabaseTest::class,
    ExpenseOnDatabaseTest::class,
    CountsOnDatabaseTest::class,
    CreditAndCashDrillTest::class,
    AdvancedCatalogDrillTest::class,
    AdvancedStockDrillTest::class,
    ReturnsAndPricesDrillTest::class,
    ReportsDrillTest::class,
    DocumentsDrillTest::class,
    ImportCorpusTest::class,
    CsvExportOnDatabaseTest::class,
    SummaryPackageOnDatabaseTest::class,
    DemoSessionOnDatabaseTest::class,
    PrivacyDrillTest::class,
    BackupDrillTest::class,
)
internal class AirplaneModeScopeSuite {
    companion object {
        @get:ClassRule
        @JvmStatic
        val airplane = AirplaneModeRule()
    }
}

/**
 * Enciende el modo avión antes de la primera prueba y lo deja como estaba después de la última.
 * Sin el argumento `airplaneScope=true`, o donde la prueba no puede encender el modo avión, la
 * tanda entera se salta con el motivo a la vista.
 */
internal class AirplaneModeRule : ExternalResource() {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var mode: AirplaneMode? = null

    override fun before() {
        assumeTrue(
            "Solo corre con -e $ARGUMENT true",
            InstrumentationRegistry.getArguments().getString(ARGUMENT) == "true",
        )
        val airplane = AirplaneMode(context)
        mode = airplane
        try {
            airplane.turnOn()
        } catch (failure: Throwable) {
            // Si falla antes de la primera prueba, `after` no corre: el teléfono no queda sin red.
            airplane.restore()
            throw failure
        }
    }

    override fun after() {
        mode?.restore()
        mode = null
    }

    private companion object {
        const val ARGUMENT = "airplaneScope"
    }
}
