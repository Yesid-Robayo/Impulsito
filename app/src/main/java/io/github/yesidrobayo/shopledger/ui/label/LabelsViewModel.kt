package io.github.yesidrobayo.shopledger.ui.label

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.label.LabelTemplate
import io.github.yesidrobayo.shopledger.domain.model.print.PairedPrinter
import io.github.yesidrobayo.shopledger.domain.model.print.PairedPrintersResult
import io.github.yesidrobayo.shopledger.domain.model.print.PrintResult
import io.github.yesidrobayo.shopledger.domain.rule.label.LabelItem
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.GenerateOwnBarcodesResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.RepricedProductsResult
import kotlin.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Lo que la pantalla de etiquetas lee y escribe en la base. Las pruebas dan respuestas fijas. */
interface LabelCatalog {
    /** Los productos sin archivar, de nuevo con cada cambio, como un código recién generado. */
    fun products(): Flow<List<Product>>

    fun categories(): Flow<List<Category>>

    fun calendar(): Flow<BusinessCalendarResult>

    suspend fun repriced(from: BusinessDate): RepricedProductsResult

    suspend fun generateCodes(productIds: Collection<Long>): GenerateOwnBarcodesResult
}

/**
 * Lo que la pantalla le pide a la impresora. [paired] solo se llama con el permiso concedido; las
 * pruebas usan una impresora falsa.
 */
interface LabelPrinting {
    fun paired(): PairedPrintersResult

    suspend fun printLabels(
        printer: PairedPrinter,
        labels: List<LabelItem>,
        template: LabelTemplate,
        size: LabelSize,
    ): PrintResult
}

/**
 * `Etiquetas imprimibles`: elegir qué productos y cuántas copias, la plantilla y el tamaño, e
 * imprimir en una térmica emparejada o crear una hoja PDF para el servicio de impresión del
 * sistema.
 *
 * El permiso de Bluetooth se pide solo al tocar `Imprimir` o `Configurar impresora`, nunca al
 * abrir. Un fallo de impresión no toca nada guardado y deja lo elegido intacto.
 */
class LabelsViewModel(
    private val catalog: LabelCatalog,
    private val printing: LabelPrinting,
    private val now: () -> Instant,
    onlyRepriced: Boolean = false,
) : ViewModel() {
    private val state = MutableStateFlow(
        LabelsUiState(scope = if (onlyRepriced) LabelScope.CHANGED else LabelScope.PICK),
    )
    val uiState: StateFlow<LabelsUiState> = state.asStateFlow()

    private var sheetAttempts = 0
    private var reading: Job? = null
    private var calendarRead = false

    init {
        load()
    }

    fun onAction(action: LabelsAction) {
        when (action) {
            // La navegación la resuelve la ruta.
            LabelsAction.Back -> Unit

            is LabelsAction.SelectScope -> selectScope(action.scope)

            LabelsAction.OpenPick -> state.update {
                it.copy(scope = LabelScope.PICK, sheet = LabelSheet.PRODUCTS)
            }

            is LabelsAction.TogglePicked -> state.update {
                val picked = if (action.productId in it.picked) {
                    it.picked - action.productId
                } else {
                    it.picked + action.productId
                }
                it.copy(picked = picked, removed = it.removed - action.productId)
            }

            is LabelsAction.PickCategory -> state.update {
                it.copy(categoryId = action.categoryId, sheet = null, removed = emptySet())
            }

            LabelsAction.CloseSheet -> state.update { it.copy(sheet = null) }

            is LabelsAction.SetSince -> {
                state.update {
                    it.copy(since = action.date, repriced = null, removed = emptySet())
                }
                readRepriced()
            }

            is LabelsAction.Increment -> state.update {
                val copies = (it.copies[action.productId] ?: 1) + 1
                it.copy(copies = it.copies + (action.productId to minOf(copies, MAX_COPIES)))
            }

            is LabelsAction.Decrement -> state.update {
                val copies = it.copies[action.productId] ?: 1
                if (copies > 1) {
                    it.copy(copies = it.copies + (action.productId to copies - 1))
                } else {
                    it.copy(
                        removed = it.removed + action.productId,
                        copies = it.copies - action.productId,
                    )
                }
            }

            is LabelsAction.SelectTemplate -> state.update { it.copy(template = action.template) }

            is LabelsAction.SelectSize -> state.update {
                it.copy(size = action.size, notice = null)
            }

            LabelsAction.UseWider -> state.update { current ->
                current.wider?.let { current.copy(size = it) } ?: current
            }

            LabelsAction.Print -> startPrint()

            LabelsAction.ConfigurePrinter -> askPrinter(printAfter = false)

            is LabelsAction.PermissionResult -> onPermission(action.granted)

            is LabelsAction.ChoosePrinter -> {
                val after = state.value.printAfterChoosing
                state.update {
                    it.copy(printer = action.printer, sheet = null, printAfterChoosing = false)
                }
                if (after) sendToPrinter()
            }

            LabelsAction.CreatePdf -> createSheet()

            is LabelsAction.SheetOpened -> state.update {
                it.copy(
                    sheetJob = null,
                    message = if (action.opened) it.message else LabelMessage.SheetFailed,
                )
            }

            LabelsAction.GenerateCodes -> state.update { it.copy(confirmingGenerate = true) }

            LabelsAction.DismissGenerate -> state.update { it.copy(confirmingGenerate = false) }

            LabelsAction.ConfirmGenerate -> generateCodes()

            LabelsAction.Retry -> {
                load()
                if (state.value.scope == LabelScope.CHANGED) readRepriced()
            }

            LabelsAction.MessageShown -> state.update { it.copy(message = null) }
        }
    }

    private fun load() {
        state.update { it.copy(loading = true, readError = false) }
        reading?.cancel()
        reading = viewModelScope.launch {
            combine(catalog.products(), catalog.categories()) { products, categories ->
                products to categories
            }.catch {
                state.update { it.copy(loading = false, readError = true) }
            }.collect { (products, categories) ->
                state.update {
                    it.copy(
                        loading = false,
                        readError = false,
                        products = products,
                        categories = categories.sortedBy { c -> c.details.normalizedName },
                    )
                }
            }
        }
        if (calendarRead) return
        calendarRead = true
        viewModelScope.launch {
            catalog.calendar().catch {}.collect { result ->
                val calendar = (result as? BusinessCalendarResult.Ready)?.calendar
                val today = calendar?.businessDateOf(now())
                val first = state.value.since == null
                state.update {
                    it.copy(
                        today = today,
                        weekStartDay = calendar?.weekStartDay,
                        since = it.since ?: today,
                    )
                }
                if (first && state.value.scope == LabelScope.CHANGED) readRepriced()
            }
        }
    }

    private fun selectScope(scope: LabelScope) {
        state.update {
            it.copy(
                scope = scope,
                removed = emptySet(),
                sheet = when (scope) {
                    LabelScope.PICK -> LabelSheet.PRODUCTS
                    LabelScope.CATEGORY -> LabelSheet.CATEGORY
                    LabelScope.CHANGED -> null
                },
            )
        }
        if (scope == LabelScope.CHANGED) readRepriced()
    }

    private fun readRepriced() {
        val since = state.value.since ?: return
        state.update { it.copy(repriced = null) }
        viewModelScope.launch {
            when (val result = catalog.repriced(since)) {
                is RepricedProductsResult.Found -> state.update {
                    // Solo si la fecha sigue siendo la misma: otra lectura más nueva manda.
                    if (it.since == since) it.copy(repriced = result.productIds) else it
                }

                is RepricedProductsResult.StorageFailed -> state.update {
                    it.copy(repriced = emptySet(), readError = true)
                }
            }
        }
    }

    private fun startPrint() {
        val current = state.value
        if (current.labelCount == 0 || current.printing) return
        when {
            current.size.paper == null -> createSheet()
            current.printer == null -> askPrinter(printAfter = true)
            else -> sendToPrinter()
        }
    }

    private fun askPrinter(printAfter: Boolean) {
        state.update { it.copy(askingPermission = true, printAfterChoosing = printAfter) }
    }

    private fun onPermission(granted: Boolean) {
        if (!granted) {
            state.update {
                it.copy(
                    askingPermission = false,
                    printAfterChoosing = false,
                    notice = LabelNotice.PermissionDenied,
                )
            }
            return
        }
        when (val result = printing.paired()) {
            is PairedPrintersResult.Listed -> state.update {
                it.copy(
                    askingPermission = false,
                    printers = result.printers,
                    sheet = LabelSheet.PRINTERS,
                    notice = null,
                )
            }

            is PairedPrintersResult.Unavailable -> state.update {
                it.copy(
                    askingPermission = false,
                    printAfterChoosing = false,
                    notice = LabelNotice.PrintersUnavailable(result.reason),
                )
            }
        }
    }

    private fun sendToPrinter() {
        val current = state.value
        val printer = current.printer ?: return
        val labels = current.printable
        if (labels.isEmpty()) return
        state.update { it.copy(printing = true, notice = null) }
        viewModelScope.launch {
            val result = printing.printLabels(printer, labels, current.template, current.size)
            state.update {
                when (result) {
                    is PrintResult.Sent ->
                        it.copy(printing = false, message = LabelMessage.Sent(printer))

                    is PrintResult.Failed ->
                        it.copy(printing = false, notice = LabelNotice.PrintFailed(result.reason))
                }
            }
        }
    }

    private fun createSheet() {
        val current = state.value
        if (current.labelCount == 0) return
        // La hoja tiene su propio ancho: los códigos se revisan con él, no con el de la térmica.
        val sheet = current.copy(size = LabelSize.SHEET)
        sheetAttempts++
        state.update {
            it.copy(
                notice = null,
                sheetJob = SheetJob(sheet.printable, current.template, sheetAttempts),
            )
        }
    }

    private fun generateCodes() {
        val ids = state.value.plan.withoutCode.map { it.productId }
        state.update { it.copy(confirmingGenerate = false, generating = true) }
        viewModelScope.launch {
            val result = catalog.generateCodes(ids)
            state.update {
                when (result) {
                    // Los códigos llegan con la lista de productos, que vuelve a emitir.
                    is GenerateOwnBarcodesResult.Done -> it.copy(generating = false)

                    is GenerateOwnBarcodesResult.StorageFailed ->
                        it.copy(generating = false, message = LabelMessage.SaveFailed)
                }
            }
        }
    }

    private companion object {
        /** Un tope para que mantener el más no imprima un rollo entero por error. */
        const val MAX_COPIES = 99
    }
}
