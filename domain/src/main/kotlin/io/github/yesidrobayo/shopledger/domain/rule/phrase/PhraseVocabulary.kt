package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Scale

/**
 * El vocabulario de un idioma, escrito como lo dice la gente, con tildes y en minúsculas o no: se
 * normaliza al compilarlo en [PhraseLexicon]. Ampliarlo es agregar frases a estas listas; ninguna
 * regla del intérprete nombra una palabra.
 *
 * Los números van en milésimas: "dos" es 2000 y "medio", 500.
 */
internal class PhraseVocabulary(
    /** Relleno del habla que se descarta: "eh", "bueno", "hoy". */
    val fillers: List<String>,
    /** Frases que dicen que la venta o la compra queda a crédito. */
    val credit: List<String>,
    val verbs: Map<VerbKind, List<String>>,
    /** Antes de un total de toda la operación: "todo por", "en total". */
    val totalBefore: List<String>,
    /** Después de un total de toda la operación: "30000 en total". */
    val totalAfter: List<String>,
    /** Antes del precio de una unidad: "a 500". */
    val unitPriceBefore: List<String>,
    /** Antes del total de una línea: "por 1500". */
    val lineTotalBefore: List<String>,
    /** Dicen que el monto es por unidad: "cada uno", "c/u". */
    val eachAfter: List<String>,
    /** Seguidas de una unidad, dicen que el monto es por esa unidad: "el kilo", "por kilo". */
    val perUnitLeads: List<String>,
    /** Entre un monto y lo que se pagó con él: "20000 por 10 kilos". */
    val amountFor: List<String>,
    /** Separan una línea de otra: "y", "más", "también". */
    val separators: List<String>,
    /** Fracción que sigue a un número o a una unidad: "y medio". */
    val continuations: Map<String, Long>,
    /** Frases que son un número: "un par de". */
    val fixedNumbers: Map<String, Long>,
    /** Palabras que son un número por sí mismas. */
    val numberWords: Map<String, Long>,
    /** Multiplican lo leído sin cerrar el grupo: "hundred" en "two hundred fifty". */
    val multipliers: Map<String, Long>,
    /** Multiplican lo leído y cierran un grupo: "mil", "k", "lucas". */
    val magnitudes: Map<String, Long>,
    /** Unen palabras de un número: "treinta y cinco". */
    val conjunctions: List<String>,
    /** Artículos que dicen una unidad: "a bread". */
    val oneArticles: List<String>,
    /** Cantidades que no se pueden leer: "unos", "varios". */
    val vague: List<String>,
    val articles: List<String>,
    /** Unen la cantidad o la unidad con el producto: "de", "of". */
    val connectors: List<String>,
    /** Palabras que no distinguen un producto de otro. */
    val stopwords: List<String>,
    val big: List<String>,
    val small: List<String>,
    /** Antes del nombre de una persona: "a María", "donde Don Ramiro". */
    val partyPrepositions: List<String>,
    /** Antes de un nombre que no está en el catálogo: "a Pedro". */
    val unknownPartyPrepositions: List<String>,
    /** Antes de a quién va lo de la línea, sin cifras después: "panes para pedro". */
    val toPrepositions: List<String>,
    val honorifics: List<String>,
    /** Antes de un método de pago: "en", "con". */
    val paymentPrepositions: List<String>,
    /** Sinónimos de los métodos de pago iniciales, por su clave. */
    val paymentSynonyms: Map<String, List<String>>,
    /** Sinónimos de las categorías de gasto iniciales, por su clave. */
    val categorySynonyms: Map<String, List<String>>,
    /** Abren una receta por lo que rinde: "con" en "con 1 kilo de cera me salen 8 velas". */
    val yieldLeads: List<String>,
    /** Reemplazan el nombre del producto anterior: "one" en "a vanilla one". */
    val pronounHeads: List<String>,
    /** Palabras de pregunta: una frase que empieza con una no registra nada. */
    val questions: List<String>,
    /** Pronombres que quedan sueltos al sacar el verbo: "se me dañaron" deja "se" y "me". */
    val pronouns: List<String>,
    /** Envases que no son unidades del catálogo: "3 botellas de leche" son 3 de leche. */
    val containers: List<String>,
    /** Lo que se salta antes del nombre de un producto nuevo: "producto", "llamado". */
    val productLead: List<String>,
    val priceKeywords: List<String>,
    val costKeywords: List<String>,
    /** Relleno de un cambio de precio o de costo: "ahora", "es". */
    val changeFillers: List<String>,
    /** Reglas de singular para comparar. */
    val singularRules: List<Pair<String, String>>,
    /** Reglas de singular para proponer un nombre nuevo, legibles. */
    val displayRules: List<Pair<String, String>>,
)

/** Palabras de número con su valor en unidades enteras, pasadas a milésimas. */
internal fun wholeNumbers(vararg entries: Pair<String, Int>): Map<String, Long> =
    entries.associate { (word, value) -> word to value * Scale.MILLI }

/** Lo que vale igual en los tres idiomas: unidades, monedas y relleno universal. */
internal object CommonVocabulary {
    val fillers: List<String> = listOf("eh", "ehh", "hmm", "mmm", "mm", "ah", "ok", "okay")

    /**
     * Nombres de moneda que se ignoran después de un número: el intérprete solo entiende el número.
     */
    val currencies: List<String> = listOf(
        "peso", "pesos", "pesitos", "dólar", "dólares", "dolar", "dolares", "dollar", "dollars",
        "buck", "bucks",
        "real", "reales", "reais", "euro", "euros", "sol", "soles", "bolívar", "bolívares",
        "quetzal", "quetzales",
        "lempira", "lempiras", "colón", "colones", "córdoba", "córdobas", "guaraní", "guaraníes",
        "plata",
        "pila", "pilas", "pratas", "r", "us", "usd", "eur",
    )

    /** Jerga de dinero que multiplica: un número dicho con ella es un monto, no una cantidad. */
    val moneyMagnitudes: List<String> = listOf("luca", "lucas", "palo", "palos", "grand", "k")

    /** Sinónimos de las unidades iniciales, por su clave, en los tres idiomas a la vez. */
    val unitSynonyms: Map<String, List<String>> = mapOf(
        "unit" to listOf(
            "unidad", "unidades", "und", "unid", "pieza", "unit", "piece", "unidade", "peça",
        ),
        "kilogram" to
            listOf(
                "kilo",
                "kg",
                "kgs",
                "kilogramo",
                "kilogram",
                "kilogramme",
                "quilo",
                "quilograma",
            ),
        "gram" to listOf("gramo", "gr", "grs", "g", "gram", "gramme", "grama"),
        "pound" to listOf("libra", "lb", "lbs", "pound"),
        "ounce" to listOf("onza", "oz", "ounce", "onça"),
        "liter" to listOf("litro", "lt", "lts", "l", "liter", "litre"),
        "milliliter" to listOf("mililitro", "ml", "cc", "milliliter", "millilitre"),
        "gallon" to listOf("galón", "gal", "gallon", "galão"),
        "meter" to listOf("metro", "mt", "mts", "m", "meter", "metre"),
        "box" to listOf("caja", "box", "caixa"),
        "pack" to listOf("paquete", "paq", "pack", "package", "pacote"),
        "dozen" to listOf("docena", "dozen", "dúzia"),
    )
}
