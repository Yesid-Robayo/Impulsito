package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.sale.PaymentRejection
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure

/**
 * Resultado de [RegisterSaleUseCase]. Cualquier rechazo deshace la transacción entera: una venta
 * rechazada no deja cabecera, líneas, filas del libro ni pagos.
 *
 * No existe un rechazo por falta de stock: vender nunca se bloquea por eso, y el saldo puede quedar
 * negativo.
 */
sealed interface RegisterSaleResult {
    /**
     * La venta quedó guardada con [saleId], que también es su número visible. [pointsEarned] son
     * los puntos que ganó su cliente, cero si no ganó ninguno.
     *
     * [moneyAccountSkipped] avisa que algún pago no entró en la cuenta de su método, porque está
     * archivada o lleva otra moneda que el negocio: la venta vale igual, pero esa cuenta no se movió
     * y la pantalla lo dice.
     */
    data class Registered(
        val saleId: Long,
        val pointsEarned: Long = 0L,
        val moneyAccountSkipped: Boolean = false,
    ) : RegisterSaleResult

    /** La venta no tiene líneas. */
    data object EmptySale : RegisterSaleResult

    /** La venta tiene más líneas que el tope de un documento. */
    data object TooManyLines : RegisterSaleResult

    /** La nota pasa de su largo máximo. */
    data object NoteTooLong : RegisterSaleResult

    /**
     * La moneda del negocio todavía no está confirmada: nada con dinero se guarda sin saber en qué
     * moneda está.
     */
    data object CurrencyNotConfirmed : RegisterSaleResult

    /**
     * Este teléfono no conoce la zona del negocio [storedZoneId], así que no hay cómo fechar la
     * venta. La persona elige una zona que el teléfono conozca.
     */
    data class UnknownTimeZone(val storedZoneId: String) : RegisterSaleResult

    /** La fecha elegida es posterior a hoy en la zona del negocio. */
    data object FutureDate : RegisterSaleResult

    /** El método de pago no existe. */
    data object PaymentMethodNotFound : RegisterSaleResult

    /** El método de pago está archivado: ya no se ofrece al cobrar. */
    data object PaymentMethodArchived : RegisterSaleResult

    /** El método es a crédito, y vender a crédito exige un cliente, que esta venta no tiene. */
    data object PaymentNeedsCustomer : RegisterSaleResult

    /**
     * El cliente elegido ya no se puede usar: no existe, se archivó o se anonimizó mientras se
     * vendía. La persona elige otro o vende sin cliente.
     */
    data object CustomerUnavailable : RegisterSaleResult

    /** El vencimiento del pago a crédito es anterior a la fecha de la venta. */
    data object CreditDueBeforeSale : RegisterSaleResult

    /** La línea número [index], contada desde cero, no se puede vender, por [reason]. */
    data class LineRejected(val index: Int, val reason: LineRejection) : RegisterSaleResult

    /**
     * La lista de precios elegida ya no existe o se archivó mientras se vendía: los precios que se
     * ven no serían los que se guardan. Hay que elegir otra.
     */
    data object PriceListUnavailable : RegisterSaleResult

    /**
     * El total, el costo o la reposición de la venta, la comisión de un pago o la suma de los
     * pagos pasan del tope.
     */
    data object SaleTotalTooLarge : RegisterSaleResult

    /** La base no pudo escribir, por [failure]. No quedó nada guardado. */
    data class StorageFailed(val failure: StorageFailure) : RegisterSaleResult

    /**
     * El pago número [index] de los que escribió la persona, contado desde cero, no se puede
     * guardar, por [reason].
     */
    data class PaymentRejected(val index: Int, val reason: PaymentRejection) : RegisterSaleResult

    /**
     * Los pagos suman [paid] y la venta cobra [amountDue]: sin esa igualdad no se confirma. La
     * pantalla muestra la diferencia para que la persona la corrija.
     */
    data class PaymentsDoNotMatch(val amountDue: Money, val paid: Money) : RegisterSaleResult

    /**
     * El descuento escrito para toda la venta supera lo que suman sus líneas tras sus propios
     * descuentos: dejaría la venta con un total negativo.
     */
    data object OrderDiscountTooLarge : RegisterSaleResult

    /**
     * La propina es negativa, pasa del tope de un documento, o se escribió con las propinas
     * apagadas: el cobro no la ofrecía.
     */
    data object TipNotAccepted : RegisterSaleResult

    /**
     * Se pidió canjear puntos sin cliente, con el programa de puntos apagado o con puntos negativos:
     * el cobro no ofrecía ese canje.
     */
    data object PointsNotAvailable : RegisterSaleResult

    /** El cliente tiene [available] puntos, menos de los que se pidió canjear. */
    data class NotEnoughPoints(val available: Long) : RegisterSaleResult

    /**
     * El descuento de los puntos, sumado al ajuste escrito, supera lo que suman las líneas: dejaría
     * la venta con un total negativo.
     */
    data object PointsAboveSale : RegisterSaleResult
}

/** Por qué una línea de la venta no se puede vender. */
enum class LineRejection {
    /** El producto no existe. */
    PRODUCT_NOT_FOUND,

    /** El producto está archivado: no se ofrece en ventas nuevas. */
    PRODUCT_ARCHIVED,

    /**
     * El producto se arma con componentes y su receta no se puede descontar: la venta no tiene con
     * qué leer las recetas, o la receta tiene un tercer nivel de combos, que solo deja una base
     * dañada.
     */
    COMPONENTS_NOT_SUPPORTED,

    /** El producto lleva lotes. Vender por lotes todavía no está disponible. */
    LOTS_NOT_SUPPORTED,

    /** La cantidad no es positiva o pasa del tope de una línea. */
    INVALID_QUANTITY,

    /** La unidad del producto se cuenta entera, y la cantidad trae decimales. */
    FRACTION_NOT_ALLOWED,

    /** El precio es negativo o pasa del tope de un precio por unidad. */
    INVALID_PRICE,

    /** El descuento es negativo, pasa del bruto de la línea o de 100 %. */
    INVALID_DISCOUNT,

    /** El bruto, el costo o la reposición de la línea pasan del tope de una línea. */
    AMOUNT_TOO_LARGE,

    /**
     * El producto no se vende en la unidad de la línea: no tiene esa conversión, o se quitó de la
     * venta después de elegirla.
     */
    UNIT_UNAVAILABLE,

    /**
     * El producto tiene variantes: no se vende directo, porque no tiene stock propio. Se elige una
     * de sus variantes.
     */
    PRODUCT_HAS_VARIANTS,

    /**
     * Con esta cantidad, un componente del combo consumiría menos de media milésima: no queda
     * nada que descontar ni que guardar. Se vende más del combo, o se ajusta la receta.
     */
    COMPONENT_ROUNDS_TO_ZERO,
}
