package io.github.yesidrobayo.shopledger.domain.usecase.production

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityCheck
import io.github.yesidrobayo.shopledger.domain.rule.production.ProductionInput

/**
 * Lo que pide la persona al producir: [quantity] milésimas de [productId], consumiendo [inputs].
 *
 * Los insumos llegan ya calculados desde la receta, con la cantidad que la persona pudo cambiar
 * para esta tanda. Cada uno es una hoja, en milésimas de su unidad base, y aparece una sola vez.
 *
 * [lot] es el lote al que entra lo producido, solo en un producto que se controla por lotes. Sin
 * él, entra al lote sin asignar del producto.
 */
data class ProductionRequest(
    val productId: Long,
    val quantity: QuantityMilli,
    val inputs: List<ProductionInput>,
    val note: String?,
    val lot: ProductionLot? = null,
)

/**
 * El lote al que entra lo producido: el del código [code], tal como lo escribió la persona. Si el
 * producto ya tiene un lote con ese código, lo producido se suma a él, como otra entrega del mismo
 * lote en una compra, y su vencimiento no cambia. Si no, se crea con el vencimiento [expiry], si
 * lo tiene. Un código en blanco entra al lote sin asignar.
 */
data class ProductionLot(val code: String, val expiry: BusinessDate?)

/** Por qué no se puede usar un insumo de la producción. */
enum class ProductionInputRejection {
    /** El producto no existe. */
    NOT_FOUND,

    /** Es un combo: se consume por sus componentes, que son los que deben llegar. */
    IS_COMBO,

    /** Es el mismo producto que se produce. */
    SAME_PRODUCT,

    /** Ya aparece en otro renglón de la producción. */
    REPEATED,

    /** Lleva lotes, y el registro no tiene cómo leerlos. Solo pasa sin lotes conectados. */
    LOTS_NOT_SUPPORTED,

    /** El lote elegido no es de este insumo, o no tiene nada en la ubicación de donde sale. */
    LOT_NOT_FOUND,

    /** La cantidad es cero, negativa o pasa del tope de una línea. */
    INVALID_QUANTITY,
}

/**
 * Cómo terminó registrar una producción. Cualquier rechazo deshace la transacción entera: ningún
 * insumo sale y nada entra.
 */
sealed interface RegisterProductionResult {
    /** Quedó registrada con el id [batchId], y lo producido entró por [totalCost]. */
    data class Registered(val batchId: Long, val totalCost: Money) : RegisterProductionResult

    /** El producto no existe. */
    data object NotFound : RegisterProductionResult

    /** El producto está archivado: no se produce lo que ya no se vende. */
    data object ProductArchived : RegisterProductionResult

    /** El producto no lleva stock propio: un combo o un servicio no se producen por adelantado. */
    data object NotStocked : RegisterProductionResult

    /**
     * El producto lleva lotes, y el registro no tiene cómo leerlos. Solo pasa sin lotes
     * conectados.
     */
    data object LotsNotSupported : RegisterProductionResult

    /** Llegó un lote para lo producido, y el producto no se controla por lotes. */
    data object LotNotTracked : RegisterProductionResult

    /** El código del lote de lo producido pasa de [max] caracteres. */
    data class LotCodeTooLong(val max: Int) : RegisterProductionResult

    /** El producto no tiene receta. */
    data object NoRecipe : RegisterProductionResult

    /** La cantidad a producir no vale para su unidad: [check] dice por qué. */
    data class InvalidQuantity(val check: UnitQuantityCheck) : RegisterProductionResult

    /** Sin insumos no hay costo de qué partir. */
    data object NoInputs : RegisterProductionResult

    /** Más insumos que líneas admite un documento. */
    data object TooManyInputs : RegisterProductionResult

    /** El insumo de la posición [index] no se puede usar, por [reason]. */
    data class InputRejected(val index: Int, val reason: ProductionInputRejection) :
        RegisterProductionResult

    /** La nota pasa de su largo máximo. */
    data object NoteTooLong : RegisterProductionResult

    /** Una cifra pasaría de su tope, [limit]: la cantidad, el costo o el stock que dejaría. */
    data class AboveLimit(val limit: Limit) : RegisterProductionResult

    /** Este teléfono no reconoce la zona del negocio: no hay cómo fechar la producción. */
    data object UnknownTimeZone : RegisterProductionResult

    /** La moneda del negocio sigue sin confirmar, y la producción guarda costos. */
    data object CurrencyUnconfirmed : RegisterProductionResult

    /** La base no pudo escribir, por [failure]. No quedó nada guardado. */
    data class StorageFailed(val failure: StorageFailure) : RegisterProductionResult
}
