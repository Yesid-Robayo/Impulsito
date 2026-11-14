package io.github.yesidrobayo.shopledger.ui.label

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.label.LabelTemplate
import io.github.yesidrobayo.shopledger.domain.model.print.PairedPrinter
import io.github.yesidrobayo.shopledger.domain.model.print.PaperWidth
import io.github.yesidrobayo.shopledger.domain.model.print.PrintFailure
import io.github.yesidrobayo.shopledger.domain.rule.label.Code128
import io.github.yesidrobayo.shopledger.domain.rule.label.LabelBarcodeFit
import io.github.yesidrobayo.shopledger.domain.rule.label.LabelItem
import io.github.yesidrobayo.shopledger.domain.rule.label.LabelPlan
import io.github.yesidrobayo.shopledger.domain.rule.label.LabelSelection
import kotlinx.datetime.DayOfWeek

/** Dónde sale la etiqueta: una térmica de 58 u 80 mm, o una hoja PDF para otra impresora. */
enum class LabelSize(val paper: PaperWidth?) {
    MM_58(PaperWidth.MM_58),
    MM_80(PaperWidth.MM_80),
    SHEET(null),
    ;

    /** El ancho de una etiqueta, en puntos de 203 ppp, los de una térmica. */
    val dots: Int get() = paper?.dots ?: SHEET_LABEL_DOTS

    /** El módulo más chico del código que se lee bien en esta salida. */
    val minimumModule: Int
        get() = when (paper) {
            null -> LabelBarcodeFit.MIN_SHEET_MODULE
            else -> LabelBarcodeFit.MIN_THERMAL_MODULE
        }

    companion object {
        /**
         * Una etiqueta de la hoja: un tercio de A4 menos márgenes, unos 60 mm, dibujada con los
         * puntos de una térmica y escalada al PDF.
         */
        const val SHEET_LABEL_DOTS = 480
    }
}

/**
 * Si el código de [code] cabe legible en una etiqueta de [size]. Un código que Code 128 no puede
 * escribir, como uno con una letra fuera de ASCII, tampoco cabe.
 */
fun codeFits(code: String, size: LabelSize): Boolean {
    val symbol = Code128.encode(code) ?: return false
    return LabelBarcodeFit.moduleWidth(symbol.width, size.dots, size.minimumModule) != null
}

/** Qué productos: los chips de `Qué productos`. */
enum class LabelScope {
    PICK,
    CATEGORY,

    /** `Solo lo que cambió de precio`, desde la fecha elegida. */
    CHANGED,
}

/** La hoja abierta, si hay una. */
enum class LabelSheet {
    PRODUCTS,
    CATEGORY,
    PRINTERS,
}

/** Un aviso fijo sobre la barra, que queda hasta que la persona actúa. */
@Immutable
sealed interface LabelNotice {
    /** Negó el permiso de Bluetooth: queda la hoja PDF. */
    data object PermissionDenied : LabelNotice

    /** No se pudieron leer las emparejadas, por [reason]. */
    data class PrintersUnavailable(val reason: PrintFailure) : LabelNotice

    /** El trabajo no salió, por [reason]. Nada se pierde: se puede reintentar o crear el PDF. */
    data class PrintFailed(val reason: PrintFailure) : LabelNotice
}

/** Un aviso pasajero. */
sealed interface LabelMessage {
    /** El trabajo salió del teléfono hacia [printer]. */
    data class Sent(val printer: PairedPrinter) : LabelMessage

    /** No se pudieron guardar los códigos nuevos. */
    data object SaveFailed : LabelMessage

    /** La hoja PDF no se pudo crear o abrir. */
    data object SheetFailed : LabelMessage
}

/** Una hoja PDF por crear: [labels] con sus copias, en [template]. [attempt] separa dos iguales.
 */
@Immutable
data class SheetJob(val labels: List<LabelItem>, val template: LabelTemplate, val attempt: Int)

/**
 * El estado de `Etiquetas imprimibles`. Lo elegido vive aquí y nunca se pierde por un error: un
 * fallo de impresión deja todo como estaba, con `Reintentar` y `Crear PDF`.
 *
 * @property repriced los productos que cambiaron de precio desde [since], o `null` mientras se
 * leen.
 * @property removed los que la persona quitó de la lista con el menos, en cualquier alcance.
 * @property printer la impresora elegida en esta visita. Recordarla es de la pantalla de
 *   impresoras, que llega después.
 */
@Immutable
data class LabelsUiState(
    val loading: Boolean = true,
    val readError: Boolean = false,
    val scope: LabelScope = LabelScope.PICK,
    val categoryId: Long? = null,
    val picked: List<Long> = emptyList(),
    val since: BusinessDate? = null,
    val today: BusinessDate? = null,
    val weekStartDay: DayOfWeek? = null,
    val repriced: Set<Long>? = null,
    val removed: Set<Long> = emptySet(),
    val copies: Map<Long, Int> = emptyMap(),
    val template: LabelTemplate = LabelTemplate.NAME_PRICE_CODE,
    val size: LabelSize = LabelSize.MM_58,
    val products: List<Product> = emptyList(),
    val categories: List<Category> = emptyList(),
    val sheet: LabelSheet? = null,
    val printers: List<PairedPrinter> = emptyList(),
    val printer: PairedPrinter? = null,
    val askingPermission: Boolean = false,
    val printAfterChoosing: Boolean = false,
    val printing: Boolean = false,
    val notice: LabelNotice? = null,
    val confirmingGenerate: Boolean = false,
    val generating: Boolean = false,
    val sheetJob: SheetJob? = null,
    val message: LabelMessage? = null,
) {
    // Lo derivado se calcula una vez por estado: la lista lo consulta en cada fila, y revisar si
    // un código cabe lo codifica entero.

    /** Los productos del alcance, sin los quitados, con sus copias. */
    val chosen: List<LabelItem> by lazy {
        val active = products.filter { !it.isArchived }
        val inScope = when (scope) {
            LabelScope.PICK -> {
                val byId = active.associateBy { it.id }
                picked.mapNotNull { byId[it] }
            }

            LabelScope.CATEGORY ->
                active.filter { categoryId != null && it.details.categoryId == categoryId }
                    .sortedBy { it.details.normalizedName }

            LabelScope.CHANGED ->
                active.filter { repriced.orEmpty().contains(it.id) }
                    .sortedBy { it.details.normalizedName }
        }
        inScope.filter { it.id !in removed }.map { product ->
            LabelItem(
                productId = product.id,
                name = product.details.name,
                price = product.salePrice,
                code = product.details.barcode,
                copies = copies[product.id] ?: 1,
            )
        }
    }

    /** Lo que entra y lo que queda por fuera con la plantilla elegida. */
    val plan: LabelPlan by lazy { LabelSelection.plan(chosen, template) }

    /** Los que tienen código pero no cabe legible en este tamaño. */
    val tooLong: List<LabelItem> by lazy {
        if (template.showsCode) {
            plan.printable.filter { item -> !codeFits(requireNotNull(item.code), size) }
        } else {
            emptyList()
        }
    }

    /** Los ids de [tooLong], para marcar sus filas. */
    val tooLongIds: Set<Long> by lazy { tooLong.map { it.productId }.toSet() }

    /** Lo que se imprime de verdad: lo que entra y cuyo código cabe. */
    val printable: List<LabelItem> by lazy {
        plan.printable.filter { it.productId !in tooLongIds }
    }

    /** Cuántas etiquetas salen, con las copias. */
    val labelCount: Int get() = printable.sumOf { it.copies }

    /** Si la lista todavía espera datos. */
    val waiting: Boolean get() = loading || (scope == LabelScope.CHANGED && repriced == null)

    /** Sin impresora elegida para una térmica: la hoja PDF siempre funciona. */
    val needsPrinter: Boolean get() = size.paper != null && printer == null

    /** El tamaño mayor que se ofrece si el código no cabe, o `null` si ya es el mayor. */
    val wider: LabelSize?
        get() = when (size) {
            LabelSize.MM_58 -> LabelSize.MM_80
            LabelSize.MM_80, LabelSize.SHEET -> null
        }
}

/** Lo que la persona hace en la pantalla. */
sealed interface LabelsAction {
    data object Back : LabelsAction

    data class SelectScope(val scope: LabelScope) : LabelsAction

    /** `Elegir` desde el vacío: abre la hoja de productos. */
    data object OpenPick : LabelsAction

    data class TogglePicked(val productId: Long) : LabelsAction

    data class PickCategory(val categoryId: Long) : LabelsAction

    data object CloseSheet : LabelsAction

    data class SetSince(val date: BusinessDate) : LabelsAction

    data class Increment(val productId: Long) : LabelsAction

    /** Con una sola copia, el menos quita el producto de la lista. */
    data class Decrement(val productId: Long) : LabelsAction

    data class SelectTemplate(val template: LabelTemplate) : LabelsAction

    data class SelectSize(val size: LabelSize) : LabelsAction

    /** `Usar el tamaño mayor`, cuando un código no cabe. */
    data object UseWider : LabelsAction

    /** `Imprimir {n} etiquetas` o, con la hoja, `Crear PDF`. */
    data object Print : LabelsAction

    /** `Configurar impresora`: pide el permiso y muestra las emparejadas. */
    data object ConfigurePrinter : LabelsAction

    data class PermissionResult(val granted: Boolean) : LabelsAction

    data class ChoosePrinter(val printer: PairedPrinter) : LabelsAction

    data object CreatePdf : LabelsAction

    data class SheetOpened(val opened: Boolean) : LabelsAction

    data object GenerateCodes : LabelsAction

    data object ConfirmGenerate : LabelsAction

    data object DismissGenerate : LabelsAction

    data object Retry : LabelsAction

    data object MessageShown : LabelsAction
}
