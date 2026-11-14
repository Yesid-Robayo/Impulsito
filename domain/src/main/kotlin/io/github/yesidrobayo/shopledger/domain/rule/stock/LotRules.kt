package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.LotPart
import io.github.yesidrobayo.shopledger.domain.model.stock.LotStock

/** Cómo quedó un código de lote escrito por la persona. */
sealed interface LotCodeCheck {
    /** Vale: [code] es el texto recortado que se guarda. */
    data class Valid(val code: String) : LotCodeCheck

    /** En blanco: un lote con código necesita alguno. */
    data object Blank : LotCodeCheck

    /** Pasa de [max] caracteres. */
    data class TooLong(val max: Int) : LotCodeCheck
}

/** Cómo quedaron las series de una entrada, una por unidad. */
sealed interface SerialCodesCheck {
    /** Valen todas: [codes] son los textos recortados, en el orden en que se escribieron. */
    data class Valid(val codes: List<String>) : SerialCodesCheck

    /** La serie de la posición [index] no vale, por [problem]. */
    data class Invalid(val index: Int, val problem: LotCodeCheck) : SerialCodesCheck

    /** [code] se escribió dos veces: cada serie es una sola unidad. */
    data class Repeated(val code: String) : SerialCodesCheck
}

/**
 * Las reglas de los lotes, los vencimientos y las series.
 *
 * El lote no cambia el costo: el producto sigue con un solo costo promedio, y cada fila de salida
 * se valora igual que sin lotes. Lo que el lote decide es de dónde sale lo que sale. Un producto
 * que se controla por lotes lleva lote en cada fila con cantidad, y el lote sin asignar recibe lo
 * que no trae lote: es el único que puede quedar negativo, para que vender nunca se bloquee.
 */
object LotRules {
    /** Largo máximo de un código de lote o de una serie, el mismo que admite la base. */
    const val MAX_CODE_LENGTH: Int = 60

    /** Una serie es un lote de una unidad entera. */
    val SERIAL_QUANTITY: QuantityMilli = QuantityMilli.ofUnits(1L)

    /** Revisa un código de lote: se recorta, no puede quedar en blanco y tiene un largo máximo. */
    fun checkCode(raw: String): LotCodeCheck {
        val code = raw.trim()
        return when {
            code.isEmpty() -> LotCodeCheck.Blank
            code.length > MAX_CODE_LENGTH -> LotCodeCheck.TooLong(MAX_CODE_LENGTH)
            else -> LotCodeCheck.Valid(code)
        }
    }

    /**
     * Revisa las series de una entrada, una por unidad: cada una es un código de lote válido, y
     * ninguna se repite, porque el código es único dentro del producto.
     */
    fun checkSerialCodes(raw: List<String>): SerialCodesCheck {
        val codes = ArrayList<String>(raw.size)
        val seen = HashSet<String>()
        raw.forEachIndexed { index, text ->
            when (val check = checkCode(text)) {
                is LotCodeCheck.Valid -> {
                    if (!seen.add(check.code)) return SerialCodesCheck.Repeated(check.code)
                    codes += check.code
                }

                LotCodeCheck.Blank, is LotCodeCheck.TooLong ->
                    return SerialCodesCheck.Invalid(index, check)
            }
        }
        return SerialCodesCheck.Valid(codes)
    }

    /**
     * El orden de salida de los lotes: primero el que vence antes, después los que no vencen, y
     * los empates por el más antiguo, que es el de id menor. Solo entran los lotes con código y
     * con stock: el lote sin asignar no se ofrece, porque absorbe lo que falte.
     */
    fun exitOrder(lots: List<LotStock>): List<LotStock> = lots
        .filter { !it.isUnassigned && it.onHand > QuantityMilli.ZERO }
        .sortedWith(compareBy<LotStock>({ exitKey(it) }, { it.lotId }))

    /**
     * La clave del orden de salida: la fecha `AAAA-MM-DD`, que ordena igual que el texto, o una que
     * va después de todas, para los lotes sin vencimiento.
     */
    private fun exitKey(lot: LotStock): String = when (val expiry = lot.expiryDate) {
        null -> AFTER_EVERY_DATE
        else -> expiry.toString()
    }

    /** Ordena después de cualquier fecha `AAAA-MM-DD`: `~` va después de las cifras. */
    private const val AFTER_EVERY_DATE = "~"

    /**
     * Reparte una salida de [quantity] milésimas, positivas, entre los lotes [lots] de una
     * ubicación. Sale primero del lote elegido [chosenLotId], si tiene stock, y luego en el orden
     * de [exitOrder]. Ningún lote con código da más de lo que tiene: lo que falte sale del lote
     * sin asignar, una parte con `lotId` nulo, que puede quedar negativo. Elegir el lote sin
     * asignar saca todo de él.
     */
    fun splitOutflow(
        quantity: QuantityMilli,
        lots: List<LotStock>,
        chosenLotId: Long?,
    ): List<LotPart> {
        require(quantity > QuantityMilli.ZERO) { "Una salida siempre saca algo" }
        val ordered = exitOrder(lots)
        val chosen = ordered.firstOrNull { it.lotId == chosenLotId }
        val sequence = if (chosen == null) ordered else listOf(chosen) + (ordered - chosen)
        val unassignedChosen = lots.any { it.lotId == chosenLotId && it.isUnassigned }
        val parts = ArrayList<LotPart>()
        var left = quantity
        if (!unassignedChosen) {
            for (lot in sequence) {
                if (left == QuantityMilli.ZERO) break
                val take = minOf(left, lot.onHand)
                parts += LotPart(lot.lotId, take)
                left -= take
            }
        }
        if (left > QuantityMilli.ZERO) parts += LotPart(lotId = null, quantity = left)
        return parts
    }
}
