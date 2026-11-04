package io.github.yesidrobayo.shopledger.domain.model.purchase

/**
 * La moneda en que se pagó una compra, cuando no es la del libro, y su cotización.
 *
 * Los montos de la compra siempre van en la moneda del libro: esto solo deja constancia de la
 * moneda original. [currencyCode] es un código ISO 4217 de tres letras mayúsculas, y [rateMicro]
 * son las unidades enteras de la moneda del libro por una unidad entera de esa moneda, por un
 * millón.
 *
 * Lanza [IllegalArgumentException] con un código o una cotización imposibles: la base tampoco los
 * aceptaría.
 */
data class PurchaseForeignCurrency(val currencyCode: String, val rateMicro: Long) {
    init {
        require(currencyCode.length == CODE_LENGTH && currencyCode.all { it in 'A'..'Z' }) {
            "El código de moneda no es de tres letras mayúsculas"
        }
        require(rateMicro > 0L) { "La cotización debe ser mayor que cero" }
    }

    private companion object {
        const val CODE_LENGTH = 3
    }
}
