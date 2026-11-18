package io.github.yesidrobayo.shopledger.ui.phrase

import android.content.res.Resources
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.phrase.CardFollowUp
import io.github.yesidrobayo.shopledger.domain.model.phrase.NumberStyle
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseCatalog
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLanguage
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseInterpreter
import io.github.yesidrobayo.shopledger.domain.rule.phrase.correctedBy
import io.github.yesidrobayo.shopledger.domain.rule.phrase.followedBy
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ChangePriceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveConversionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveRecipeUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.RecordCustomerPaymentUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.DocumentMoney
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyLedgerWriter
import io.github.yesidrobayo.shopledger.domain.usecase.payable.PaySupplierUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.phrase.PhraseReadResult
import io.github.yesidrobayo.shopledger.domain.usecase.phrase.PhraseTextPort
import io.github.yesidrobayo.shopledger.domain.usecase.phrase.PhraseWriters
import io.github.yesidrobayo.shopledger.domain.usecase.phrase.ReadPhraseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.phrase.RegisterPhraseResult
import io.github.yesidrobayo.shopledger.domain.usecase.phrase.RegisterPhraseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.production.RegisterProductionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.production.RegisterProductionsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.AdjustStockUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CorrectCostUseCase
import io.github.yesidrobayo.shopledger.localization.SeedText
import io.github.yesidrobayo.shopledger.navigation.ScreenDependencies
import io.github.yesidrobayo.shopledger.ui.format.AppFormats

/**
 * Lo que `Decir o escribir` hace con la base abierta: leer una frase, corregir la tarjeta con otra
 * frase o sumársela, y registrarla. Cada operación se registra con el caso de uso de siempre, armado como en su
 * pantalla, así lo dicho guarda exactamente lo mismo, con sus reglas y en su transacción. Los productos nuevos y las
 * medidas dichas en la tarjeta se guardan con la operación, en esa misma transacción.
 */
internal class PhraseUseCases(
    val read: suspend (String) -> PhraseReadResult,
    val correct: (PhraseDraft, String, PhraseCatalog) -> PhraseDraft?,
    val follow: (PhraseDraft, String, PhraseReadResult) -> CardFollowUp,
    val register: suspend (PhraseDraft) -> PhraseOutcome,
)

/**
 * Los casos de uso de la frase sobre la base abierta, con los textos y los formatos de la interfaz:
 * [resources] en el idioma de la app y [formats] del negocio.
 */
internal fun phraseUseCases(
    dependencies: ScreenDependencies,
    resources: Resources,
    formats: AppFormats,
): PhraseUseCases {
    val ports = dependencies.ports
    val container = dependencies.container
    val clock = container.clock
    val calendar = BusinessCalendarSource(ports.settings)
    val currencyLock = CurrencyLock(ports.settings)
    val texts = AndroidPhraseTexts(resources, formats)
    val recipe =
        SaveRecipeUseCase(ports.transactions, ports.products, ports.productComponents, clock)
    val read =
        ReadPhraseUseCase(
            settings = ports.settings,
            texts = texts,
            products = ports.products,
            productUnits = ports.productUnits,
            components = ports.productComponents,
            units = ports.units,
            paymentMethods = ports.paymentMethods,
            suppliers = ports.suppliers,
            customers = ports.customers,
            expenseCategories = ports.expenseCategories,
            productCategories = ports.categories,
        )
    val writers =
        PhraseWriters.of(
            sale =
                RegisterSaleUseCase(
                    transactions = ports.transactions,
                    settings = ports.settings,
                    products = ports.products,
                    sales = ports.sales,
                    payments = ports.salePayments,
                    units = ports.units,
                    systemRows = ports.systemRows,
                    ledger = ports.stockLedger,
                    methodNames = container.paymentMethodNames,
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
                    locations = ports.locations,
                    lots = ports.stockLots,
                    quotes = ports.quotes,
                    orders = ports.orders,
                ),
            purchase =
                RegisterPurchaseUseCase(
                    transactions = ports.transactions,
                    settings = ports.settings,
                    products = ports.products,
                    purchases = ports.purchases,
                    suppliers = ports.suppliers,
                    units = ports.units,
                    systemRows = ports.systemRows,
                    ledger = ports.stockLedger,
                    taxRates = ports.taxRates,
                    clock = clock,
                    productUnits = ports.productUnits,
                    alerts = ports.lowStockAlerts,
                    categories = ports.categories,
                    supplierLedger = ports.supplierLedger,
                    money = DocumentMoney(ports.moneyAccounts, ports.moneyLedger),
                    locations = ports.locations,
                    lots = ports.stockLots,
                ),
            expense =
                RegisterExpenseUseCase(
                    transactions = ports.transactions,
                    settings = ports.settings,
                    expenses = ports.expenses,
                    categories = ports.expenseCategories,
                    clock = clock,
                    money = DocumentMoney(ports.moneyAccounts, ports.moneyLedger),
                    recurring = ports.recurringExpenses,
                ),
            // Las producciones de una tarjeta, con la receta que se dijo, en una transacción.
            productions =
                RegisterProductionsUseCase(
                    ports.transactions,
                    recipe,
                    RegisterProductionUseCase(
                        ports.transactions, ports.products, ports.units, ports.productComponents,
                        ports.productionBatches, ports.systemRows, ports.stockLedger,
                        ports.lowStockAlerts, calendar, currencyLock, clock, ports.stockLots,
                    ),
                ),
            adjustment =
                AdjustStockUseCase(
                    ports.transactions, ports.products, ports.units, ports.systemRows,
                    ports.stockLedger, ports.lowStockAlerts, calendar, currencyLock, clock,
                    ports.stockLots, ports.locations, ports.locationStock,
                ),
            customerPayment =
                RecordCustomerPaymentUseCase(
                    transactions = ports.transactions,
                    settings = ports.settings,
                    customers = ports.customers,
                    ledger = ports.customerLedger,
                    accounts = ports.moneyAccounts,
                    money = MoneyLedgerWriter(ports.moneyAccounts, ports.moneyLedger),
                    clock = clock,
                ),
            supplierPayment =
                PaySupplierUseCase(
                    transactions = ports.transactions,
                    settings = ports.settings,
                    suppliers = ports.suppliers,
                    ledger = ports.supplierLedger,
                    clock = clock,
                    accounts = ports.moneyAccounts,
                    moneyLedger = ports.moneyLedger,
                ),
            cost =
                CorrectCostUseCase(
                    ports.transactions,
                    ports.products,
                    ports.systemRows,
                    ports.stockLedger,
                    calendar,
                    currencyLock,
                    clock,
                ),
            price =
                ChangePriceUseCase(
                    ports.transactions,
                    ports.products,
                    ports.systemRows,
                    ports.priceHistory,
                    calendar,
                    currencyLock,
                    clock,
                ),
            product =
                CreateProductUseCase(
                    ports.transactions, ports.products, ports.categories, ports.suppliers,
                    ports.units, ports.systemRows, ports.priceHistory, ports.stockLedger,
                    ports.lowStockAlerts, calendar, currencyLock, clock, ports.mediaAssets,
                ),
            recipe = recipe,
            conversion =
                SaveConversionUseCase(
                    ports.transactions,
                    ports.products,
                    ports.units,
                    ports.productUnits,
                    clock,
                ),
            transactions = ports.transactions,
        )
    val register =
        RegisterPhraseUseCase(
            writers = writers,
            products = ports.products,
            productUnits = ports.productUnits,
            units = ports.units,
            components = ports.productComponents,
            paymentMethods = ports.paymentMethods,
        )
    val moneyDecimals = formats.money.currencyDecimals
    return PhraseUseCases(
        read = { read(it) },
        correct = { draft, text, catalog ->
            // Una moneda con más decimales de los que lee el intérprete no se corrige: la frase se
            // lee entonces como nueva, y la lectura tampoco la entiende.
            if (moneyDecimals in 0..PhraseInterpreter.MAX_MONEY_DECIMALS) {
                draft.correctedBy(
                    text,
                    texts.language(),
                    texts.numberStyle(),
                    moneyDecimals,
                    catalog,
                )
            } else {
                null
            }
        },
        follow = { draft, text, result ->
            // Con esa moneda, la lectura tampoco entendió la frase: la tarjeta sigue como estaba.
            if (moneyDecimals in 0..PhraseInterpreter.MAX_MONEY_DECIMALS) {
                draft.followedBy(
                    text,
                    result.reading,
                    texts.language(),
                    texts.numberStyle(),
                    moneyDecimals,
                    result.catalog,
                )
            } else {
                CardFollowUp.NotUnderstood
            }
        },
        register = { outcomeOf(register(it)) },
    )
}

/**
 * Cómo terminó registrar, sin textos. El motivo de un rechazo no se detalla: la tarjeta dice que no
 * se pudo con esos datos, y lo escrito sigue para corregirlo.
 */
internal fun outcomeOf(result: RegisterPhraseResult): PhraseOutcome = when (result) {
    is RegisterPhraseResult.Registered -> PhraseOutcome.Registered(result.intent, result.documentId)

    is RegisterPhraseResult.Incomplete -> PhraseOutcome.Incomplete(result.gaps)

    is RegisterPhraseResult.Rejected -> PhraseOutcome.Rejected

    is RegisterPhraseResult.StorageFailed ->
        PhraseOutcome.SaveFailed(outOfSpace = result.failure.isOutOfSpace)
}

/**
 * Lo que la lectura de una frase necesita de la interfaz: el idioma de la app, la forma de los
 * números del negocio y los nombres de las filas iniciales, todo en memoria.
 */
internal class AndroidPhraseTexts(
    private val resources: Resources,
    private val formats: AppFormats,
) : PhraseTextPort {
    override fun language(): PhraseLanguage = phraseLanguageOf(formats.uiLocale.language)

    override fun numberStyle(): NumberStyle = numberStyleOf(formats.quantity.inputSymbols)

    override fun seedName(table: SeedTable, systemKey: String): String =
        SeedText.nameRes(table, systemKey)?.let(resources::getString) ?: systemKey

    override fun seedUnitSymbol(systemKey: String): String? =
        SeedText.unitSymbol(resources, systemKey, null)
}

/** El idioma de la frase según el de la interfaz; uno que no conoce lee en inglés. */
internal fun phraseLanguageOf(language: String): PhraseLanguage = when (language) {
    "es" -> PhraseLanguage.ES
    "pt" -> PhraseLanguage.PT
    else -> PhraseLanguage.EN
}

/**
 * La forma de los números con que dicta el teclado: la del formato regional. Una región que no
 * agrupa cifras toma el otro separador común, que nunca es el decimal.
 */
internal fun numberStyleOf(symbols: NumberSymbols): NumberStyle {
    val decimal = symbols.decimalSeparator
    val grouping = symbols.grouping?.separator ?: if (decimal == COMMA) PERIOD else COMMA
    return NumberStyle(decimal = decimal, grouping = grouping)
}

private const val COMMA = ','
private const val PERIOD = '.'
