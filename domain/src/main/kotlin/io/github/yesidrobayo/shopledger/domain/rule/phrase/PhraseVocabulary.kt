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
    /**
     * Antes de lo que costó lo de la línea, dicho después del producto: "que me costaron 4 mil".
     * Es el total de la línea, o el costo de una unidad si se dice "cada uno" o "el kilo".
     */
    val costClauses: List<String>,
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
    /** Lo que se dice de un producto nuevo: su uso, su categoría. Ver [TraitVocabulary]. */
    val traits: TraitVocabulary,
    /**
     * Si en los nombres del idioma lo que importa va al final, como en inglés: "Orange juice" es
     * un jugo. Así "oranges" no se toma con confianza por el jugo de naranja.
     */
    val headLast: Boolean = false,
    /** Reglas de singular para comparar. */
    val singularRules: List<Pair<String, String>>,
    /** Reglas de singular para proponer un nombre nuevo, legibles. */
    val displayRules: List<Pair<String, String>>,
    /** Lo que nunca se registra desde una frase. Ver [Refusals]. */
    val refusals: Refusals,
    /** Formas del habla que cambian cómo se lee una línea o un monto. Ver [SpeechForms]. */
    val speech: SpeechForms,
)

/**
 * Lo que dice que la frase no es una operación que se registra así, aunque nombre productos o
 * montos. Es lo más importante que el intérprete reconoce: leerlas como una venta o un gasto
 * registraría algo que no pasó.
 */
internal class Refusals(
    /**
     * Frases de lo que se hace en otra pantalla o no se hace: devoluciones, anular o borrar,
     * retiros y préstamos, deudas dichas como hecho, cotizaciones, listas de precios, cambios de
     * todos los precios, lo que apareció, y una venta nombrada sin decir qué se vendió.
     */
    val phrases: List<String>,
    /** Niegan el verbo que sigue: "no vendí", "não paguei". Solo cuentan pegadas a un verbo. */
    val negations: List<String>,
    /** Dicen que todavía no pasó: "mañana", "voy a", "tengo que". */
    val notYet: List<String>,
    /** Después de una cifra, un porcentaje: "un 10 por ciento". */
    val percents: List<String> = emptyList(),
    /**
     * Dicen un precio al por mayor: "mayorista", "wholesale". Un cambio de precio que lo dice no
     * es el precio del producto, sino el de una lista que la app no lleva, y no se registra; una
     * compra al por mayor sí es una compra.
     */
    val wholesale: List<String> = emptyList(),
)

/**
 * Formas del habla que el intérprete reconoce para no leerlas mal:
 *
 * - [softFillers] son relleno salvo antes del nombre de un producto: "este" en "eh, este, vendí"
 *   se salta, y en "vendí este pan" dice uno.
 * - [demonstratives] dicen una unidad delante de un producto: "este pan", "this bread".
 * - [lineCopulas] abren una línea que solo dice la cantidad o el monto: "fueron 10 kilos", "son
 *   6 mil".
 * - [batches] cuentan tandas: "2 tandas de 20 panes" son 40.
 * - [decimalWords] separan decimales dichos en palabras: "uno coma cinco", "one point two".
 * - [tenderedBefore] y [tenderedAfter] rodean con cuánto pagó, que no es el precio: "con un
 *   billete de 20 mil", "with a 20 bill". Si hay palabras de después, son obligatorias.
 * - [shares] reparten el pago entre dos métodos: "la mitad en efectivo".
 * - [genericParties] nombran a alguien sin decir quién: "un cliente", "a customer". No son un
 *   producto ni una persona del catálogo, pero sí dicen que alguien se llevó algo.
 * - [recipeEach] abren una receta por unidad: "cada empanada lleva", "por cada 20 panes uso".
 * - [priceSetters] y [setterMarkers] fijan un precio con un verbo que solo no lo dice: "pon el
 *   kilo de papa a 2.900", "set potatoes to 1.30".
 * - [creditWraps] rodean a una persona en una forma de crédito: "on Charles's tab".
 * - [fromPrepositions] nombran a quien vendió algo que no está en el catálogo, al final de la
 *   frase: "comprei 10 quilos de cenoura do Pedro".
 * - [selfFixes] corrigen lo último que se dijo dentro de la misma frase: "vendí 3 panes, perdón,
 *   4". Vale lo que se dice después.
 * - [createLeads] crean un producto si abren la frase sin otro verbo: "nuevo: galletas a 2 mil".
 * - [priceCopulas] dicen un precio entre el producto y el monto, sin otro verbo: "el pan es 600",
 *   "rolls are 0.70".
 * - [supplierRoles] dicen que la persona nombrada es un proveedor aunque no esté en el catálogo:
 *   "a Julio, el proveedor". No son un producto ni una categoría: salen de la frase.
 */
internal class SpeechForms(
    val softFillers: List<String> = emptyList(),
    val demonstratives: List<String> = emptyList(),
    val lineCopulas: List<String> = emptyList(),
    val batches: List<String> = emptyList(),
    val decimalWords: List<String> = emptyList(),
    val tenderedBefore: List<String> = emptyList(),
    val tenderedAfter: List<String> = emptyList(),
    val shares: List<String> = emptyList(),
    val genericParties: List<String> = emptyList(),
    val recipeEach: List<String> = emptyList(),
    val priceSetters: List<String> = emptyList(),
    val setterMarkers: List<String> = emptyList(),
    val creditWraps: List<Pair<String, String>> = emptyList(),
    val fromPrepositions: List<String> = emptyList(),
    val selfFixes: List<String> = emptyList(),
    val createLeads: List<String> = emptyList(),
    val priceCopulas: List<String> = emptyList(),
    val supplierRoles: List<String> = emptyList(),
)

/**
 * Cómo se describe un producto que no existe, para crearlo ya con su uso y su categoría: "esto es
 * un insumo", "es para vender", "en la categoría bebidas". Esas palabras nunca son una línea.
 */
internal class TraitVocabulary(
    /** Abren una descripción: "esto es", "es", "son", "como". Solas no dicen nada. */
    val leads: List<String>,
    /** Artículos entre la apertura y el tipo: "un", "una". */
    val articles: List<String>,
    /**
     * Tipos que solo cuentan después de una apertura o de un artículo: "es un **insumo**", "es un
     * **servicio**". Solos podrían ser el nombre de un producto.
     */
    val kinds: Map<String, SpokenKind>,
    /** Tipos que se entienden solos, sin apertura: "para vender", "materia prima". */
    val plainKinds: Map<String, SpokenKind>,
    /** Antes del nombre de una categoría: "en la categoría", "categoría". */
    val categoryBefore: List<String>,
    /** Después del nombre de una categoría, en inglés: "in the drinks **category**". */
    val categoryAfter: List<String>,
    /** Abren el nombre de una categoría que se cierra con [categoryAfter]: "**in the** drinks". */
    val categoryAfterLeads: List<String>,
    /**
     * Palabras que nunca son de un producto y terminan su nombre: "esto", "es". Lo que queda
     * después no se propone como un producto nuevo.
     */
    val descriptionWords: List<String>,
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

    /**
     * Abreviaturas que se escriben con punto sin terminar la oración: tratamientos y unidades,
     * en los tres idiomas. Ya normalizadas.
     */
    val abbreviations: Set<String> = setOf(
        "sr", "sra", "srta", "dr", "dra", "don", "mr", "mrs", "ms", "st", "kg", "kgs", "gr", "grs",
        "g", "lb", "lbs", "lt", "lts", "l", "ml", "cc", "oz", "gal", "mt", "mts", "m", "und",
        "uni", "paq", "pkg", "doz", "etc", "nro", "num", "av", "cra",
    )

    /** Jerga de dinero que multiplica: un número dicho con ella es un monto, no una cantidad. */
    val moneyMagnitudes: List<String> = listOf("luca", "lucas", "palo", "palos", "grand", "k")

    /**
     * Centésimas de la unidad de la moneda, en cualquier moneda que las tenga: "cincuenta
     * centavos" es medio.
     */
    val cents: List<String> = listOf(
        "cent",
        "cents",
        "centavo",
        "centavos",
        "céntimo",
        "céntimos",
    )

    /**
     * Cuántas unidades trae una unidad inicial que cuenta, por su clave: una docena son doce en
     * todas partes. Las demás, como la caja, no tienen tamaño fijo.
     */
    val countFactors: Map<String, Long> = mapOf("dozen" to 12L)

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
