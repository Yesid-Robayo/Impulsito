package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransferLineRejection
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityCheck
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityRule

/**
 * Lo que se revisa de una transferencia antes de escribirla, y los saldos que dejan sus filas.
 *
 * Una transferencia no toca la cantidad total, el costo ni el valor de un producto: cada línea es
 * una salida del origen y una entrada al destino, que suman cero. Por eso la salida deja el saldo
 * del producto bajo, y la entrada lo devuelve a donde estaba.
 */
object StockTransferRules {
    /** Los caracteres más largos de la nota de una transferencia. */
    const val NOTE_MAX: Int = 200

    /**
     * Por qué no se puede transferir [quantity] de [product], o `null` si se puede. Con
     * [allowsFraction] la unidad base admite decimales.
     *
     * Transferir más de lo que hay en el origen no se bloquea: lo deja en negativo, y la pantalla
     * lo avisa antes, como al vender.
     */
    fun lineRejection(
        product: Product?,
        allowsFraction: Boolean,
        quantity: QuantityMilli,
    ): StockTransferLineRejection? = when {
        product == null -> StockTransferLineRejection.PRODUCT_NOT_FOUND

        product.isArchived -> StockTransferLineRejection.PRODUCT_ARCHIVED

        product.details.stockMode != ProductStockMode.TRACKED ->
            StockTransferLineRejection.NOT_TRACKED

        product.details.tracksLots -> StockTransferLineRejection.LOTS_NOT_SUPPORTED

        UnitQuantityRule.check(quantity, allowsFraction) != UnitQuantityCheck.Valid ->
            StockTransferLineRejection.INVALID_QUANTITY

        !Limit.LINE_QUANTITY_MILLI.allows(quantity.milli) ->
            StockTransferLineRejection.QUANTITY_TOO_LARGE

        else -> null
    }

    /**
     * El saldo del producto después de la salida de una línea: el de antes menos lo que sale. La
     * entrada que la sigue lo deja otra vez en [onHand].
     */
    fun balanceAfterExit(onHand: QuantityMilli, quantity: QuantityMilli): QuantityMilli =
        onHand - quantity
}
