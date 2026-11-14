package io.github.yesidrobayo.shopledger.data.repository

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.backup.SnapshotCreator
import io.github.yesidrobayo.shopledger.data.backup.SnapshotResult
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.data.media.MediaImporter
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.CodeMatch
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomFieldDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomFieldType
import io.github.yesidrobayo.shopledger.domain.model.catalog.ImageImport
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductPhotoChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLine
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantAxes
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantValues
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDraft
import io.github.yesidrobayo.shopledger.domain.model.report.ProductSales
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnSettlement
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturnRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettingsInput
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.rule.ReportPeriods
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ProductSearch
import io.github.yesidrobayo.shopledger.domain.rule.report.PeriodRankings
import io.github.yesidrobayo.shopledger.domain.rule.report.RankedProduct
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateVariantsResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateVariantsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.FindProductByCodeUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.GenerateOwnBarcodesResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.GenerateOwnBarcodesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ImportProductPhotoUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveTagSearchIndexUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveCustomFieldUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveExtraResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveModifiersResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveModifiersUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductExtrasResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductExtrasUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveRecipeResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveRecipeUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveTagUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.EditPaymentMethodUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.SavePaymentMethodResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.returns.RegisterSaleReturnResult
import io.github.yesidrobayo.shopledger.domain.usecase.returns.RegisterSaleReturnUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleUseCase
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Simulacro del catálogo avanzado sobre una base nueva, por el camino de la app: los puertos de la
 * base abierta y los casos de uso armados como los arman sus pantallas. Un día de una tienda de
 * regalos inventada: dos fotos traídas por el importador real, una camiseta con variantes de dos
 * ejes, un kit de té y una caja sorpresa que lleva el kit adentro, campos propios y una etiqueta,
 * códigos propios generados y buscados, ventas de variantes y de los dos combos, la devolución
 * de un kit, y una venta con extras, la misma miel como extra de dos líneas y suelta, anulada
 * por error.
 *
 * El stock de cada hoja y de cada variante, el costo congelado de cada línea y de cada hoja, la
 * caja, la venta neta y el costo de lo vendido del día, y lo vendido por variante y por producto
 * principal se comparan con valores calculados a mano. Cada consulta de invariantes del modelo de
 * datos devuelve cero filas. Las fotos quedan en sus archivos, con su fila en la base, y la copia
 * de la base lleva la fila, nunca la imagen.
 *
 * La tienda, los productos, los códigos, las fotos y los montos son inventados. La moneda es
 * `XTS`, el código de pruebas de ISO 4217, con dos decimales: los montos van en su unidad menor.
 */
@RunWith(AndroidJUnit4::class)
internal class AdvancedCatalogDrillTest : CatalogDatabaseTest() {
    // La base nace sin moneda, como en un teléfono nuevo, y la persona la confirma en su pantalla.
    override val confirmsCurrency: Boolean = false

    /** Un reloj que avanza un segundo en cada lectura, todo dentro del mismo día del negocio. */
    private val ticks = AtomicLong(CREATED_AT)
    private val clock = Clock { Instant.fromEpochMilliseconds(ticks.addAndGet(1_000L)) }

    private lateinit var media: File
    private lateinit var inputs: File
    private lateinit var snapshot: File

    @Before
    fun createFolders() {
        val run = "catalog-drill-" + Random.nextLong().toULong()
        media = File(context.filesDir, run)
        inputs = File(context.cacheDir, run).apply { mkdirs() }
        snapshot = File(context.cacheDir, "$run.db")
    }

    @After
    fun deleteFolders() {
        media.deleteRecursively()
        inputs.deleteRecursively()
        snapshot.delete()
    }

    @Test
    fun aDayOfVariantsCombosPhotosAndCodesMatchesHandWrittenBooks() = runBlocking {
        val ports = DataPorts(database, file, Dispatchers.IO, clock, RestoreLock { false })
        val tx = ports.transactions
        val calendar = BusinessCalendarSource(ports.settings)

        // Configuración, como en sus pantallas: la moneda y el efectivo enlazado a la caja.
        assertEquals(
            SaveRegionalSettingsResult.Saved,
            SaveRegionalSettingsUseCase(tx, ports.settings, clock)(REGION),
        )
        val drawer = count("SELECT id FROM money_accounts WHERE system_key = 'CASH'")
        val cashMethod = count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")
        val cash = requireNotNull(ports.paymentMethods.findById(cashMethod)).details
        assertEquals(
            SavePaymentMethodResult.Saved(cashMethod),
            EditPaymentMethodUseCase(tx, ports.paymentMethods, ports.mediaAssets, clock)(
                cashMethod,
                PaymentMethodDraft(
                    name = cash.name,
                    kind = cash.kind,
                    feeRate = cash.feeRate,
                    feeFixed = cash.feeFixed,
                    settlementDays = cash.settlementDays,
                    priceAdjustment = cash.priceAdjustment,
                    moneyAccountId = drawer,
                    instructionsText = cash.instructionsText,
                ),
            ),
        )

        // Los casos de uso de cada pantalla, con los mismos puertos que les da la app.
        val createProduct = CreateProductUseCase(
            tx, ports.products, ports.categories, ports.suppliers, ports.units, ports.systemRows,
            ports.priceHistory, ports.stockLedger, ports.lowStockAlerts, calendar,
            CurrencyLock(ports.settings), clock, ports.mediaAssets,
        )
        val createVariants = CreateVariantsUseCase(
            tx, ports.products, ports.productVariants, ports.units, ports.systemRows,
            ports.priceHistory, ports.stockLedger, ports.lowStockAlerts, calendar,
            CurrencyLock(ports.settings), clock,
        )
        val saveRecipe = SaveRecipeUseCase(tx, ports.products, ports.productComponents, clock)
        val importPhoto = ImportProductPhotoUseCase(
            MediaImporter(context.contentResolver, { media }, Dispatchers.IO),
        )
        val saveField = SaveCustomFieldUseCase(tx, ports.customFields, clock)
        val saveTag = SaveTagUseCase(tx, ports.tags, clock)
        val saveExtras =
            SaveProductExtrasUseCase(tx, ports.products, ports.customFields, ports.tags, clock)
        val generateCodes =
            GenerateOwnBarcodesUseCase(tx, ports.products, ports.productUnits, clock)
        val findCode = FindProductByCodeUseCase(tx, ports.products, ports.productUnits)
        val saveModifiers =
            SaveModifiersUseCase(tx, ports.products, ports.productModifiers, clock)
        val registerSale = RegisterSaleUseCase(
            transactions = tx,
            settings = ports.settings,
            products = ports.products,
            sales = ports.sales,
            payments = ports.salePayments,
            units = ports.units,
            systemRows = ports.systemRows,
            ledger = ports.stockLedger,
            methodNames = PaymentMethodNames { key -> "Initial ${key.lowercase()}" },
            taxRates = ports.taxRates,
            clock = clock,
            heldSales = ports.heldSales,
            productUnits = ports.productUnits,
            alerts = ports.lowStockAlerts,
            customers = ports.customers,
            customerLedger = ports.customerLedger,
            loyaltyLedger = ports.loyaltyLedger,
            moneyAccounts = ports.moneyAccounts,
            moneyLedger = ports.moneyLedger,
            priceLists = ports.priceLists,
            productPrices = ports.productPrices,
            recipes = ports.productComponents,
            lineComponents = ports.saleLineComponents,
        )
        val registerReturn = RegisterSaleReturnUseCase(
            transactions = tx,
            settings = ports.settings,
            products = ports.products,
            sales = ports.sales,
            returns = ports.saleReturns,
            units = ports.units,
            ledger = ports.stockLedger,
            clock = clock,
            alerts = ports.lowStockAlerts,
            moneyAccounts = ports.moneyAccounts,
            moneyLedger = ports.moneyLedger,
            customers = ports.customers,
            customerLedger = ports.customerLedger,
            lineComponents = ports.saleLineComponents,
        )
        val voidSale = VoidSaleUseCase(
            transactions = tx,
            settings = ports.settings,
            products = ports.products,
            sales = ports.sales,
            ledger = ports.stockLedger,
            clock = clock,
            alerts = ports.lowStockAlerts,
            customerLedger = ports.customerLedger,
            loyaltyLedger = ports.loyaltyLedger,
            payments = ports.salePayments,
            moneyAccounts = ports.moneyAccounts,
            moneyLedger = ports.moneyLedger,
        )

        // 1. Dos fotos elegidas por la persona: el importador las recodifica a sus archivos.
        val shirtPhoto = imported(importPhoto(photo("camiseta.jpg", Color.rgb(200, 40, 40))))
        val boxPhoto = imported(importPhoto(photo("caja.jpg", Color.rgb(40, 90, 200))))

        // 2. Los productos sueltos: el té, con un código de fábrica, la taza y una envoltura sin
        // stock que cuesta 300 fijos. La camiseta es el principal de las variantes, con su foto.
        val tea = saved(createProduct(TEA, QuantityMilli(10_000)))
        val cup = saved(createProduct(CUP, QuantityMilli(10_000)))
        val wrap = saved(createProduct(WRAP))
        val shirt = saved(
            createProduct(
                ProductForm("Camiseta inventada", photo = ProductPhotoChange.Use(shirtPhoto)),
            ),
        )

        // 3. Las variantes, en dos ejes: S y M por Rojo y Azul, cada una con su libro.
        val variants = createVariants(
            shirt,
            VariantAxes("Talla", "Color"),
            listOf(
                variant("S", "Rojo", 25_000, 14_000, 4_000),
                variant("M", "Rojo", 25_000, 14_000, 5_000),
                variant("M", "Azul", 26_000, 15_000, 6_000),
            ),
        )
        val (sRed, mRed, mBlue) = (variants as CreateVariantsResult.Created).productIds

        // 4. Los combos, con receta de dos niveles: el kit lleva dos tés y una taza, y la caja
        // sorpresa lleva un kit, una camiseta M azul y la envoltura.
        val kit = saved(createProduct(KIT))
        val box = saved(
            createProduct(BOX.copy(photo = ProductPhotoChange.Use(boxPhoto))),
        )
        assertEquals(
            SaveRecipeResult.Saved,
            saveRecipe(kit, listOf(RecipeLine(tea, QuantityMilli(2_000)), recipe(cup))),
        )
        assertEquals(
            SaveRecipeResult.Saved,
            saveRecipe(box, listOf(recipe(kit), recipe(mBlue), recipe(wrap))),
        )

        // 5. Un campo propio de cada tipo y una etiqueta, en el té y en la caja. Ninguno entra
        // en un cálculo: el día de abajo sale igual con ellos o sin ellos.
        val brand = field(saveField, "Marca", CustomFieldType.TEXT)
        val weight = field(saveField, "Peso", CustomFieldType.NUMBER)
        val expiry = field(saveField, "Vence", CustomFieldType.DATE)
        val organic = field(saveField, "Orgánico", CustomFieldType.BOOLEAN)
        val gift = (saveTag(null, "Para regalo") as SaveExtraResult.Saved).id
        val teaValues = mapOf(
            brand to "Montaña inventada",
            weight to "0250.50",
            expiry to "2027-03-31",
            organic to "true",
        )
        assertEquals(SaveProductExtrasResult.Saved, saveExtras(tea, teaValues, setOf(gift)))
        assertEquals(SaveProductExtrasResult.Saved, saveExtras(box, emptyMap(), setOf(gift)))

        // 6. Los códigos propios, como en las etiquetas: el té ya tiene el suyo y no se toca, la
        // camiseta M roja y el kit reciben `P` y su id con 8 cifras.
        assertEquals(
            GenerateOwnBarcodesResult.Done(
                generated = mapOf(mRed to ownCode(mRed), kit to ownCode(kit)),
                clashes = emptyList(),
                skipped = listOf(tea),
            ),
            generateCodes(listOf(tea, mRed, kit)),
        )

        // 7. Venta de variantes: dos camisetas S rojas y una M azul, en efectivo. 76.000.
        val shirtSale = registered(
            registerSale(SaleRequest(listOf(line(sRed, 2_000), line(mBlue, 1_000)), cashMethod)),
        )
        // 8. Venta de los dos combos: una caja sorpresa a 45.000 y un kit a 12.000. 57.000.
        val comboSale = registered(
            registerSale(SaleRequest(listOf(line(box, 1_000), line(kit, 1_000)), cashMethod)),
        )
        // 9. Venta de dos kits más, a 12.000: 24.000.
        val kitSale = registered(registerSale(SaleRequest(listOf(line(kit, 2_000)), cashMethod)))
        // 10. Un kit de esa venta vuelve, con reembolso de la caja: sus hojas regresan al stock.
        val kitLine = ports.sales.findLines(kitSale).single().id
        val kitReturn = (
            registerReturn(
                SaleReturnRequest(
                    kitSale,
                    listOf(ReturnLineRequest(kitLine, QuantityMilli(1_000))),
                    ReturnSettlement.Refund(drawer),
                ),
            ) as RegisterSaleReturnResult.Registered
            ).saleReturnId
        // 11. El lector lee el código propio de la camiseta M roja y la vende a 25.000. El de
        // fábrica del té se encuentra también con las 12 cifras, sin el cero, y un código que
        // nadie tiene no encuentra nada.
        val scanned = findCode(ownCode(mRed)) as CodeMatch.Found
        assertEquals(mRed, scanned.product.id)
        assertEquals(null, scanned.conversion)
        assertEquals(tea, (findCode("123456789012") as CodeMatch.Found).product.id)
        assertTrue(findCode("P99999999") is CodeMatch.Unknown)
        val scannedSale = registered(
            registerSale(SaleRequest(listOf(line(scanned.product.id, 1_000)), cashMethod)),
        )

        // 12. Un pastel con dos extras, en su orden: miel, con stock, y crema, sin stock y con
        // 200 fijos.
        val cake = saved(createProduct(CAKE, QuantityMilli(10_000)))
        val honey = saved(createProduct(HONEY, QuantityMilli(10_000)))
        val cream = saved(createProduct(CREAM))
        assertEquals(SaveModifiersResult.Saved, saveModifiers(cake, listOf(honey, cream)))
        assertEquals(
            listOf(honey, cream),
            ports.productModifiers.findByProduct(cake).map { it.modifierProductId },
        )
        // 13. Una venta con extras: dos pasteles con miel y crema, otro con miel, y una miel
        // suelta. La miel va en dos líneas como extra y en una sola. 25.600.
        val extrasSale = registered(
            registerSale(
                SaleRequest(
                    listOf(
                        line(cake, 2_000),
                        line(honey, 2_000, parentIndex = 0),
                        line(cream, 2_000, parentIndex = 0),
                        line(cake, 1_000),
                        line(honey, 1_000, parentIndex = 3),
                        line(honey, 1_000),
                    ),
                    cashMethod,
                ),
            ),
        )
        val extrasLines = ports.sales.findLines(extrasSale).map { it.id }
        assertEquals(
            listOf(
                "$cake - 2000 6000 2000 4000 DIRECT",
                "$honey ${extrasLines[0]} 2000 1500 500 1000 DIRECT",
                "$cream ${extrasLines[0]} 2000 800 200 400 NONE",
                "$cake - 1000 6000 2000 2000 DIRECT",
                "$honey ${extrasLines[3]} 1000 1500 500 500 DIRECT",
                "$honey - 1000 1500 500 500 DIRECT",
            ),
            rows(
                "SELECT product_id || ' ' || COALESCE(parent_line_id, '-') || ' ' || " +
                    "quantity_milli || ' ' || unit_price || ' ' || unit_cost || ' ' || " +
                    "line_cost || ' ' || stock_effect FROM sale_lines " +
                    "WHERE sale_id = $extrasSale ORDER BY id",
            ),
        )
        assertEquals(
            Money(25_600),
            requireNotNull(ports.sales.findById(extrasSale)).content.total,
        )
        // 14. Esa venta se registró por error y se anula desde su detalle: cada línea, también
        // cada extra, devuelve su stock, y la caja devuelve lo cobrado.
        assertEquals(
            VoidSaleResult.Voided,
            voidSale(extrasSale, VoidReason.ENTRY_ERROR, null),
        )

        // Cada línea con su costo congelado: las variantes al de su libro, la caja a 21.700 (el
        // kit de 6.400, la camiseta de 15.000 y la envoltura de 300) y el kit a 6.400 (dos tés
        // de 1.200 y una taza de 4.000).
        assertEquals(
            listOf(
                "$shirtSale $sRed 2000 25000 14000 28000 DIRECT",
                "$shirtSale $mBlue 1000 26000 15000 15000 DIRECT",
                "$comboSale $box 1000 45000 21700 21700 COMPONENTS",
                "$comboSale $kit 1000 12000 6400 6400 COMPONENTS",
                "$kitSale $kit 2000 12000 6400 12800 COMPONENTS",
                "$scannedSale $mRed 1000 25000 14000 14000 DIRECT",
            ),
            rows(
                "SELECT sale_id || ' ' || product_id || ' ' || quantity_milli || ' ' || " +
                    "unit_price || ' ' || unit_cost || ' ' || line_cost || ' ' || " +
                    "stock_effect FROM sale_lines WHERE sale_id <> $extrasSale ORDER BY id",
            ),
        )
        // Las hojas de la caja, congeladas: las del kit de adentro, la camiseta y la envoltura,
        // que no lleva stock y cuesta su fijo.
        val boxLine = ports.sales.findLines(comboSale).first().id
        assertEquals(
            listOf(
                "$tea 2000 1 1200 2400",
                "$cup 1000 1 4000 4000",
                "$mBlue 1000 1 15000 15000",
                "$wrap 1000 0 300 300",
            ),
            leaves(boxLine),
        )
        assertEquals(listOf("$tea 4000 1 1200 4800", "$cup 2000 1 4000 8000"), leaves(kitLine))

        // El stock, fila por fila: tipo, cantidad, costo, valor y promedio después. Cada hoja
        // sale por cada combo que la lleva, y las del kit devuelto vuelven a su costo congelado.
        assertEquals(
            listOf(
                "OPENING_BALANCE 10000 12000 12000 1200",
                "SALE -2000 -2400 9600 1200",
                "SALE -2000 -2400 7200 1200",
                "SALE -4000 -4800 2400 1200",
                "SALE_RETURN 2000 2400 4800 1200",
            ),
            stockRows(tea),
        )
        assertEquals(
            listOf(
                "OPENING_BALANCE 10000 40000 40000 4000",
                "SALE -1000 -4000 36000 4000",
                "SALE -1000 -4000 32000 4000",
                "SALE -2000 -8000 24000 4000",
                "SALE_RETURN 1000 4000 28000 4000",
            ),
            stockRows(cup),
        )
        assertEquals(
            listOf(
                "OPENING_BALANCE 4000 56000 56000 14000",
                "SALE -2000 -28000 28000 14000",
            ),
            stockRows(sRed),
        )
        assertEquals(
            listOf(
                "OPENING_BALANCE 5000 70000 70000 14000",
                "SALE -1000 -14000 56000 14000",
            ),
            stockRows(mRed),
        )
        // La camiseta M azul sale una vez sola y otra dentro de la caja.
        assertEquals(
            listOf(
                "OPENING_BALANCE 6000 90000 90000 15000",
                "SALE -1000 -15000 75000 15000",
                "SALE -1000 -15000 60000 15000",
            ),
            stockRows(mBlue),
        )
        // Los extras salen y vuelven como cualquier línea: el pastel en sus dos líneas, y la miel
        // como extra de cada una y suelta. Cada fila de la venta anulada recibe la suya opuesta.
        assertEquals(
            listOf(
                "OPENING_BALANCE 10000 20000 20000 2000",
                "SALE -2000 -4000 16000 2000",
                "SALE -1000 -2000 14000 2000",
                "SALE_VOID 2000 4000 18000 2000",
                "SALE_VOID 1000 2000 20000 2000",
            ),
            stockRows(cake),
        )
        assertEquals(
            listOf(
                "OPENING_BALANCE 10000 5000 5000 500",
                "SALE -2000 -1000 4000 500",
                "SALE -1000 -500 3500 500",
                "SALE -1000 -500 3000 500",
                "SALE_VOID 2000 1000 4000 500",
                "SALE_VOID 1000 500 4500 500",
                "SALE_VOID 1000 500 5000 500",
            ),
            stockRows(honey),
        )
        assertEquals(
            "VOIDED",
            rows("SELECT status FROM sales WHERE id = $extrasSale").single(),
        )
        // El principal, la envoltura, la crema y los combos no tienen libro propio.
        for (none in listOf(shirt, wrap, kit, box, cream)) {
            assertEquals(emptyList<String>(), stockRows(none))
        }
        assertEquals(
            listOf(
                "$tea 4000 1200",
                "$cup 7000 4000",
                "$wrap 0 0",
                "$shirt 0 0",
                "$sRed 2000 14000",
                "$mRed 4000 14000",
                "$mBlue 4000 15000",
                "$kit 0 0",
                "$box 0 0",
                "$cake 10000 2000",
                "$honey 10000 500",
                "$cream 0 0",
            ),
            listOf(tea, cup, wrap, shirt, sRed, mRed, mBlue, kit, box, cake, honey, cream)
                .map { id ->
                    val product = requireNotNull(ports.products.findById(id))
                    "$id ${product.onHand.milli} ${product.averageCost.minor}"
                },
        )

        // La devolución: 12.000 de un kit, con 6.400 de costo de vuelta.
        assertEquals(
            listOf("$kitReturn REFUND 12000 6400 ACTIVE"),
            rows(
                "SELECT id || ' ' || resolution || ' ' || net_refund || ' ' || cost_returned " +
                    "|| ' ' || status FROM sale_returns ORDER BY id",
            ),
        )
        // La caja: las cuatro ventas y el reembolso. Quedan 170.000, la venta neta del día.
        assertEquals(
            listOf(
                "SALE_RECEIPT 76000 76000",
                "SALE_RECEIPT 57000 133000",
                "SALE_RECEIPT 24000 157000",
                "REFUND -12000 145000",
                "SALE_RECEIPT 25000 170000",
                "SALE_RECEIPT 25600 195600",
                "MOVEMENT_VOID -25600 170000",
            ),
            rows(
                "SELECT type || ' ' || amount || ' ' || balance_after FROM money_movements " +
                    "WHERE money_account_id = $drawer ORDER BY id",
            ),
        )

        // El día según la sección de ganancias: ventas de 76.000, 57.000, 24.000 y 25.000 menos
        // el kit devuelto, 12.000, dan 170.000; su costo, 43.000, 28.100, 12.800 y 14.000, menos
        // los 6.400 que volvieron, da 91.500.
        val day = ReportPeriods.day(
            requireNotNull(ports.sales.findById(shirtSale)).content.businessDate,
        )
        val profit = ports.periodReports.observeTotals(day).first().profit
        assertEquals(Money(182_000), profit.salesNetTotal)
        assertEquals(Money(12_000), profit.returnsNetRefund)
        assertEquals(Money(97_900), profit.salesTotalCost)
        assertEquals(Money(6_400), profit.returnsCostReturned)
        assertEquals(Money(170_000), profit.salesNetTotal - profit.returnsNetRefund)
        assertEquals(Money(91_500), profit.salesTotalCost - profit.returnsCostReturned)

        // Lo vendido por producto, con lo devuelto ya restado: cada variante con su principal y
        // sus valores, y los combos como se vendieron, nunca por sus hojas.
        val sold = ports.periodBreakdowns.observeProductSales(day).first()
        assertEquals(
            listOf(
                "$sRed 2000 50000 28000 $shirt S Rojo",
                "$mRed 1000 25000 14000 $shirt M Rojo",
                "$mBlue 1000 26000 15000 $shirt M Azul",
                "$kit 2000 24000 12800 - - -",
                "$box 1000 45000 21700 - - -",
            ),
            sold.sortedBy { it.productId }.map(::soldRow),
        )
        // Los más vendidos suman las variantes en la camiseta y las muestran debajo.
        val top = requireNotNull(PeriodRankings.top(sold).valueOrNull())
        assertEquals(
            listOf(
                "$shirt 101000 [$sRed 50000, $mBlue 26000, $mRed 25000]",
                "$box 45000 []",
                "$kit 24000 []",
            ),
            top.bySales.map { ranked(it) { product -> product.netSales.minor } },
        )
        assertEquals(
            listOf(
                "$shirt 44000 [$sRed 22000, $mRed 11000, $mBlue 11000]",
                "$box 23300 []",
                "$kit 11200 []",
            ),
            top.byProfit.map { ranked(it) { product -> product.grossProfit.minor } },
        )
        assertEquals(
            listOf(
                "$shirt 4000 [$sRed 2000, $mRed 1000, $mBlue 1000]",
                "$kit 2000 []",
                "$box 1000 []",
            ),
            top.byQuantity.single().products.map { ranked(it) { p -> p.quantity.milli } },
        )

        // Los campos propios quedan en su forma neutra, y la etiqueta encuentra lo suyo en la
        // búsqueda de la lista de productos, aunque ningún nombre la contenga.
        assertEquals(
            mapOf(
                brand to "Montaña inventada",
                weight to "250.5",
                expiry to "2027-03-31",
                organic to "true",
            ),
            ports.customFields.findValues(tea),
        )
        val active = ports.products.observe(ArchiveFilter.ACTIVE).first()
        val index = ObserveTagSearchIndexUseCase(ports.tags)().first()
        assertEquals(
            setOf(tea, box),
            ProductSearch.filter(active, "para reg", index).map { it.id }.toSet(),
        )
        assertEquals(emptyList<Long>(), ProductSearch.filter(active, "para reg").map { it.id })

        // Las fotos: dos archivos en la carpeta de imágenes y dos filas en la base, cada una
        // usada por su producto. La copia de la base lleva las filas, nunca las imágenes.
        assertEquals(
            setOf(shirtPhoto.fileName, boxPhoto.fileName),
            media.list().orEmpty().toSet(),
        )
        assertEquals(
            listOf(
                "$shirt ${shirtPhoto.sha256} PRODUCT_PHOTO ${shirtPhoto.sizeBytes}",
                "$box ${boxPhoto.sha256} PRODUCT_PHOTO ${boxPhoto.sizeBytes}",
            ),
            rows(
                "SELECT p.id || ' ' || m.sha256 || ' ' || m.kind || ' ' || m.size_bytes " +
                    "FROM products p JOIN media_assets m ON m.id = p.photo_media_id " +
                    "WHERE m.orphaned_at IS NULL ORDER BY p.id",
            ),
        )
        assertEquals(2L, count("SELECT COUNT(*) FROM media_assets"))
        val copy = SnapshotCreator(file, Dispatchers.IO).create(snapshot)
        assertTrue("$copy", copy is SnapshotResult.Created)
        val copied = snapshot.readBytes()
        for (image in listOf(shirtPhoto, boxPhoto)) {
            assertTrue(copied.contains(image.sha256.toByteArray(Charsets.US_ASCII)))
            val bytes = File(media, image.fileName).readBytes()
            assertEquals(image.sizeBytes, bytes.size.toLong())
            assertFalse(copied.contains(bytes.copyOfRange(0, SAMPLE)))
            val middle = bytes.size / 2
            assertFalse(copied.contains(bytes.copyOfRange(middle, middle + SAMPLE)))
        }

        // Cada consulta de invariantes da cero filas, también las de los combos, las de los
        // libros y la de lo devuelto frente a lo vendido.
        val labels = InvariantQueries.MODEL.map { it.label }
        for (invariant in listOf("I-05", "I-06", "I-19", "I-22")) {
            assertTrue(invariant, labels.any { it == invariant || it.startsWith("$invariant,") })
        }
        for (query in InvariantQueries.MODEL) {
            val sql = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($sql)"))
        }
    }

    /** Una foto inventada como la de una cámara: un fondo de [color] y un cuadro. */
    private fun photo(name: String, color: Int): String {
        val bitmap = Bitmap.createBitmap(1_200, 900, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(color)
            drawRect(300f, 200f, 800f, 700f, Paint().apply { this.color = Color.WHITE })
        }
        val source = File(inputs, name)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return Uri.fromFile(source).toString()
    }

    private fun imported(result: ImageImport): StoredImage =
        (result as? ImageImport.Imported)?.image ?: throw AssertionError("No se importó: $result")

    private suspend fun field(
        save: SaveCustomFieldUseCase,
        name: String,
        type: CustomFieldType,
    ): Long = (save(null, CustomFieldDraft(name, type)) as SaveExtraResult.Saved).id

    private fun variant(size: String, color: String, price: Long, cost: Long, milli: Long) =
        VariantDraft(
            VariantValues(size, color),
            salePrice = Money(price),
            cost = Money(cost),
            initialStock = QuantityMilli(milli),
        )

    private fun recipe(productId: Long) = RecipeLine(productId, QuantityMilli(1_000))

    private fun line(productId: Long, milli: Long, parentIndex: Int? = null) =
        SaleLineRequest(productId, QuantityMilli(milli), parentIndex = parentIndex)

    private fun ownCode(productId: Long): String = "P" + productId.toString().padStart(8, '0')

    private fun soldRow(sales: ProductSales): String {
        val of = sales.variantOf
        val parent = of?.let { "${it.parentId} ${it.values.first} ${it.values.second}" }
        return "${sales.productId} ${sales.quantity.milli} ${sales.netSales.minor} " +
            "${sales.cost.minor} ${parent ?: "- - -"}"
    }

    private fun ranked(product: RankedProduct, measure: (RankedProduct) -> Long): String =
        "${product.productId} ${measure(product)} " +
            product.variants.joinToString(prefix = "[", postfix = "]") {
                "${it.productId} ${measure(it)}"
            }

    /** Las hojas congeladas de la línea [lineId]: producto, cantidad, stock, costo y total. */
    private suspend fun leaves(lineId: Long): List<String> = rows(
        "SELECT component_product_id || ' ' || quantity_milli || ' ' || tracked || ' ' || " +
            "unit_cost || ' ' || line_cost FROM sale_line_components " +
            "WHERE sale_line_id = $lineId ORDER BY id",
    )

    /** Las filas de [product]: tipo, cantidad, costo, valor del stock y costo promedio después. */
    private suspend fun stockRows(product: Long): List<String> = rows(
        "SELECT type || ' ' || quantity_milli || ' ' || total_cost || ' ' || " +
            "stock_value_after || ' ' || average_cost_after FROM stock_movements " +
            "WHERE product_id = $product ORDER BY id",
    )

    private suspend fun rows(sql: String): List<String> =
        database.useReaderConnection { it.texts(sql) }

    private fun saved(result: SaveProductResult): Long =
        (result as? SaveProductResult.Saved)?.productId
            ?: throw AssertionError("No se guardó: $result")

    private fun registered(result: RegisterSaleResult): Long =
        (result as? RegisterSaleResult.Registered)?.saleId
            ?: throw AssertionError("No se registró: $result")

    /** Si [part] aparece entera, en orden, dentro de este arreglo. */
    private fun ByteArray.contains(part: ByteArray): Boolean {
        if (part.isEmpty() || part.size > size) return part.isEmpty()
        val first = part[0]
        for (start in 0..size - part.size) {
            if (this[start] != first) continue
            var matched = true
            for (offset in 1 until part.size) {
                if (this[start + offset] != part[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) return true
        }
        return false
    }

    private companion object {
        /** Cuántos bytes seguidos de una imagen se buscan en la copia. */
        const val SAMPLE = 64

        /** La región que confirma la persona: moneda de pruebas de dos decimales, sin redondeo. */
        val REGION = RegionalSettingsInput(
            currencyCode = requireNotNull(CurrencyCode.of("XTS")),
            currencyDecimals = 2,
            currencySymbol = "¤",
            cashRounding = Money.ZERO,
            formatLocaleTag = "pt-CV",
            businessTimeZoneId = "Etc/GMT-9",
            weekStartDay = DayOfWeek.MONDAY,
            csvSeparator = CsvSeparator.SEMICOLON,
        )

        /** El té trae un código de fábrica de 13 cifras, inventado, con el cero delante. */
        val TEA = ProductForm(
            name = "Té inventado",
            salePrice = Money(3_000),
            cost = Money(1_200),
            barcode = "0123456789012",
        )
        val CUP =
            ProductForm(name = "Taza inventada", salePrice = Money(9_000), cost = Money(4_000))

        /** Sin stock: cuesta 300 fijos cada vez que sale. */
        val WRAP = ProductForm(
            name = "Envoltura inventada",
            stockMode = ProductStockMode.UNTRACKED,
            salePrice = Money(1_000),
            cost = Money(300),
        )
        val KIT = ProductForm(
            name = "Kit de té inventado",
            stockMode = ProductStockMode.COMPONENTS,
            salePrice = Money(12_000),
        )
        val BOX = ProductForm(
            name = "Caja sorpresa inventada",
            stockMode = ProductStockMode.COMPONENTS,
            salePrice = Money(45_000),
        )
        val CAKE =
            ProductForm(name = "Pastel inventado", salePrice = Money(6_000), cost = Money(2_000))
        val HONEY =
            ProductForm(name = "Miel inventada", salePrice = Money(1_500), cost = Money(500))

        /** Un extra sin stock: cuesta 200 fijos cada vez que sale. */
        val CREAM = ProductForm(
            name = "Crema inventada",
            stockMode = ProductStockMode.UNTRACKED,
            salePrice = Money(800),
            cost = Money(200),
        )
    }
}
