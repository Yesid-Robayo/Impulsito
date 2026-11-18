package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.phrase.NumberStyle
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseCatalog
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLanguage

/**
 * Aplica una segunda frase corta sobre la tarjeta, en vez de leerla como una frase nueva, y
 * devuelve la tarjeta corregida. Nunca registra nada: la persona sigue viendo la tarjeta y la
 * confirma. Lee con las mismas piezas que el intérprete, así que entiende los mismos números,
 * unidades, productos, pagos y personas, en el mismo idioma y con el mismo estilo de números.
 *
 * Entiende, después de un "no", "perdón", "actually" o "na verdade" que se salta:
 *
 * - **Cantidad y unidad:** "no, eran 3", "eran 5 kilos", "actually 4". Va a la única línea o a la
 *   última que se dijo; "eran 3 panes" va a la línea del pan, y "los panes eran 3", también.
 * - **Producto:** "la harina era de maíz", "it was the big one", "era o grande". El nombre nuevo se
 *   arma con lo nombrado antes de "era", o con la cabeza del producto que tenía la línea
 *   ("Gaseosa" de "Gaseosa 400 ml" más "grande"). Si da con varios igual de posibles, el producto
 *   queda en hueco para elegir.
 * - **Quitar:** "quita las gaseosas", "sin el pan", "remove the soda", "tira o pão".
 * - **Agregar:** "agrega 2 huevos", "y también un pan", "add 2 eggs", "mais 2 ovos". Después de
 *   "y" o "también" la línea debe nombrar un producto del catálogo o decir cuántos.
 * - **Precio:** "a 600 cada uno", "eran a 600", "at 6 each". En un precio o un costo nuevo, y en un
 *   gasto o un pago, el número solo ya es el monto.
 * - **Pago y crédito:** "en efectivo", "fue por transferencia", "it was cash", "foi no pix" (si el
 *   catálogo tiene ese método), "fiado". Un método que no es el de crédito quita el crédito.
 * - **Persona:** "a María", "era de Don Ramiro", del lado que usa la operación.
 * - **Categoría** de un gasto: "era la luz".
 * - **Producto por crear:** "la harina es insumo", "es para vender", "en la categoría bebidas"
 *   cambian solo eso del producto nuevo que se nombra, o del último; "se llama harina integral" o
 *   "no, harina integral" le cambian el nombre y conservan lo demás; "no, la harina de trigo" lo
 *   cambia por uno del catálogo en la línea que se le parece, no en la última.
 * - **Operación:** "no, fue una compra", "no, compré", "it was a sale": una venta pasa a compra o
 *   al revés, con sus líneas, cantidades, precios y pago; la persona se quita, porque un cliente no
 *   es un proveedor. Desde otra operación no se cambia.
 *
 * Un verbo de operación con productos es una frase nueva, salvo después de un "no" y con un solo
 * producto: "no, vendí 3 panes" corrige la línea del pan.
 *
 * Devuelve `null` cuando no la entiende como corrección, y la pantalla la lee entonces como frase
 * nueva: si tiene un verbo de operación y nombra productos ("vendí 2 panes"), si no dice nada que
 * se pueda aplicar, si nombra un producto que no está en el catálogo para cambiar el de una línea,
 * si dice una unidad que el producto no admite, si nombra a alguien que no está en el catálogo, o
 * si lo que dice no aplica a la operación (un pago en una producción, quitar una línea de un
 * gasto).
 *
 * Cuando la corrección es ambigua no adivina. Si lo nombrado cabe en varias líneas ("las gaseosas
 * eran 3" con dos líneas de gaseosa), la cantidad o el producto que se corrigió quedan en hueco en
 * todas esas líneas para que la persona los complete; un precio dicho así vale para todas. Si
 * varios métodos de pago o varias personas son igual de posibles, ese dato queda en hueco. Quitar
 * una línea ambigua no quita ninguna y devuelve `null`.
 *
 * No sabe entre qué productos se pidió elegir en una línea en hueco, porque la tarjeta no lo
 * guarda: "era la grande" sobre esa línea no se entiende, pero "era la gaseosa grande" sí.
 */
fun PhraseDraft.correctedBy(
    text: String,
    language: PhraseLanguage,
    numberStyle: NumberStyle,
    moneyDecimals: Int,
    catalog: PhraseCatalog,
): PhraseDraft? {
    require(moneyDecimals in 0..PhraseInterpreter.MAX_MONEY_DECIMALS) {
        "Los decimales de la moneda van de 0 a 3"
    }
    return CorrectionSession(
        PhraseLexicons.of(language),
        CorrectionVocabularies.of(language),
        numberStyle,
        moneyDecimals,
        catalog,
    ).correct(this, text)
}
