package io.github.yesidrobayo.shopledger.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.phrase.NameMatch
import io.github.yesidrobayo.shopledger.domain.model.phrase.NumberStyle
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseCatalog
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseConversion
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLanguage
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseName
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseProduct
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseReading
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseUnit
import io.github.yesidrobayo.shopledger.domain.model.phrase.ProductMatch
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseInterpreter
import java.io.File
import java.math.BigDecimal
import org.junit.Test

/**
 * Mide el intérprete de frases contra el corpus escrito a ciegas: lee el catálogo de prueba y las
 * frases de cada idioma, compara cada lectura con lo esperado y escribe un informe en
 * `build/phrase-report.md`.
 *
 * Es una medición, no una puerta: solo falla si no puede leer los archivos. Cada caso queda en una
 * clase:
 *
 * - `CORRECT`: todo igual a lo esperado.
 * - `ASKED`: todo igual, y lo esperado era preguntar (elegir entre productos, crear uno nuevo o
 *   avisar un problema).
 * - `SAFE`: lo esperado era una operación y el intérprete no registró nada: la app pide repetir.
 * - `PARTIAL`: la operación es la correcta, y algo de lo demás no.
 * - `WRONG`: la operación es otra, o una línea propone con confianza un producto que no es el
 *   esperado. Es el peor caso: registraría algo equivocado sin preguntar. Sale como "Peligroso".
 *
 * Los ids impares son la medición final: el informe solo da sus totales y detalla los pares.
 *
 * Lo que un caso no dice se compara con su valor vacío (`null`, `false` o lista vacía), porque el
 * contrato los devuelve todos. Con una sola línea, un total dicho en la línea o en toda la
 * operación es lo mismo para la pantalla, así que se comparan juntos. Un nombre nuevo se compara
 * sin tildes, mayúsculas, plurales ni tratamiento.
 */
class PhraseCorpusTest {
    @Test
    fun `the interpreter is measured against the blind corpus`() {
        val fixture = Json.parse(File(FIXTURE).readText()).obj()
        val results = LANGUAGES.flatMap { (code, language) ->
            val catalog = FixtureCatalog(fixture, code)
            readCases(File("$CORPUS_DIR/$code.json")).map { case ->
                evaluate(case, code, language, catalog)
            }
        }
        val report = File("build/phrase-report.md")
        report.parentFile.mkdirs()
        report.writeText(Report(results).markdown())
        println(Report(results).summary())
    }

    private fun evaluate(
        case: CorpusCase,
        code: String,
        language: PhraseLanguage,
        catalog: FixtureCatalog,
    ): CaseResult {
        val reading = PhraseInterpreter.read(
            case.text,
            language,
            catalog.style,
            catalog.moneyDecimals,
            catalog.catalog,
        )
        val got = catalog.describe(reading)
        val expected = Expectation.of(case.expected)
        return CaseResult(code, case, expected, got, classify(expected, got))
    }

    private fun readCases(file: File): List<CorpusCase> {
        val root = Json.parse(file.readText())
        val items = root.arrOrNull() ?: CASE_LISTS.firstNotNullOf { root.obj()[it]?.arrOrNull() }
        return items.mapIndexed { index, item ->
            val obj = item.obj()
            val text = TEXT_KEYS.firstNotNullOf { obj[it]?.strOrNull() }
            val expected = EXPECTED_KEYS.firstNotNullOfOrNull { obj[it]?.objOrNull() } ?: obj
            val tags = obj["tags"]?.arrOrNull()?.mapNotNull { it.strOrNull() }
                ?: listOfNotNull(obj["tag"]?.strOrNull())
            val id = obj["id"]?.let { it.strOrNull() ?: it.render() } ?: (index + 1).toString()
            CorpusCase(id, text, tags.ifEmpty { listOf("untagged") }, expected)
        }
    }

    private companion object {
        const val FIXTURE = "src/test/resources/phrase/fixture.json"
        const val CORPUS_DIR = "src/test/resources/phrase/corpus"
        val LANGUAGES = listOf(
            "es" to PhraseLanguage.ES,
            "en" to PhraseLanguage.EN,
            "pt" to PhraseLanguage.PT,
        )
        val CASE_LISTS = listOf("cases", "items", "phrases", "corpus")
        val TEXT_KEYS = listOf("text", "phrase", "input", "frase", "utterance")
        val EXPECTED_KEYS = listOf("expected", "expect", "output", "reading")
    }
}

private data class CorpusCase(
    val id: String,
    val text: String,
    val tags: List<String>,
    val expected: Map<String, Json>,
) {
    /**
     * Si el número del id es impar: esos casos son la medición final y no se muestran en detalle.
     */
    val isOdd: Boolean get() = (id.takeLastWhile(Char::isDigit).toIntOrNull() ?: 0) % 2 == 1
}

/**
 * El veredicto de un caso. WRONG sale en el informe como "Peligroso": registraría algo equivocado.
 */
private enum class Verdict(val label: String) {
    CORRECT("CORRECT"),
    ASKED("ASKED"),
    SAFE("SAFE"),
    PARTIAL("PARTIAL"),
    WRONG("Peligroso"),
}

private data class CaseResult(
    val language: String,
    val case: CorpusCase,
    val expected: Expectation,
    val got: Expectation,
    val verdict: Verdict,
)

/** Una referencia a algo del catálogo, tal como la escribe el corpus. */
private sealed interface Ref {
    data class Key(val key: String) : Ref

    data class Choose(val keys: Set<String>) : Ref

    data class New(val name: String) : Ref

    companion object {
        fun of(json: Json?): Ref? = when {
            json == null || json is Json.Null -> null

            json is Json.Str -> Key(json.value)

            else -> {
                val obj = json.obj()
                val choose = obj["choose"]?.arrOrNull()
                if (choose !=
                    null
                ) {
                    Choose(choose.mapNotNull { it.strOrNull() }.toSet())
                } else {
                    New(obj["new"]?.strOrNull().orEmpty())
                }
            }
        }
    }
}

private data class LineView(
    val product: Ref?,
    val qty: BigDecimal?,
    val unit: String?,
    val unitPrice: BigDecimal?,
    val lineTotal: BigDecimal?,
)

/** Una lectura en los términos del corpus: claves del catálogo y números decimales. */
private data class Expectation(
    val intent: String?,
    val reason: String?,
    val lines: List<LineView>,
    val payment: Ref?,
    val party: Ref?,
    val category: Ref?,
    val total: BigDecimal?,
    val credit: Boolean,
    val target: Ref?,
    val targetQty: BigDecimal?,
    val price: BigDecimal?,
    val cost: BigDecimal?,
    val unit: String?,
    val issues: Set<String>,
) {
    /** Si lo esperado pide preguntar algo: elegir, crear o advertir. */
    val asks: Boolean
        get() = issues.isNotEmpty() ||
            (lines.map { it.product } + target + payment + party + category).any {
                it is Ref.Choose || it is Ref.New
            }

    /**
     * Con una sola línea, el total va en un solo lugar: así "por 1500" y "en total 1500" se
     * comparan igual.
     */
    fun normalized(): Expectation {
        val only = lines.singleOrNull() ?: return this
        if (total != null || only.lineTotal == null) return this
        return copy(total = only.lineTotal, lines = listOf(only.copy(lineTotal = null)))
    }

    companion object {
        fun of(obj: Map<String, Json>): Expectation = Expectation(
            intent = obj["intent"]?.strOrNull(),
            reason = obj["reason"]?.strOrNull(),
            lines = obj["lines"]?.arrOrNull().orEmpty().map { line ->
                val l = line.obj()
                LineView(
                    Ref.of(l["product"]),
                    decimal(l["qty"]),
                    l["unit"]?.strOrNull(),
                    decimal(l["unit_price"]),
                    decimal(l["line_total"]),
                )
            },
            payment = Ref.of(obj["payment"]),
            party = Ref.of(obj["party"]),
            category = Ref.of(obj["category"]),
            total = decimal(obj["total"]),
            credit = (obj["credit"] as? Json.Bool)?.value ?: false,
            target = Ref.of(obj["target"]),
            targetQty = decimal(obj["target_qty"]),
            price = decimal(obj["price"]),
            cost = decimal(obj["cost"]),
            unit = obj["unit"]?.strOrNull(),
            issues = obj["issues"]?.arrOrNull().orEmpty().mapNotNull { it.strOrNull() }.toSet(),
        )

        fun decimal(json: Json?): BigDecimal? = when (json) {
            is Json.Str -> json.value.toBigDecimalOrNull()
            is Json.Num -> json.value.toBigDecimalOrNull()
            else -> null
        }
    }
}

private fun classify(expected: Expectation, got: Expectation): Verdict {
    // No registrar nada cuando sí había una operación es seguro: la app pide repetir la frase.
    if (expected.intent != null && got.intent == null) return Verdict.SAFE
    if (expected.intent != got.intent) return Verdict.WRONG
    val confidentlyWrong = expected.lines.zip(got.lines).any { (want, have) ->
        have.product is Ref.Key && have.product != want.product
    } || (got.target is Ref.Key && got.target != expected.target)
    if (confidentlyWrong) return Verdict.WRONG
    if (!same(expected.normalized(), got.normalized())) return Verdict.PARTIAL
    return if (expected.asks) Verdict.ASKED else Verdict.CORRECT
}

private fun same(a: Expectation, b: Expectation): Boolean =
    a.reason == b.reason && a.lines.size == b.lines.size &&
        a.lines.zip(b.lines).all { (x, y) -> sameLine(x, y) } &&
        sameRef(a.payment, b.payment) && sameRef(a.party, b.party) &&
        sameRef(a.category, b.category) &&
        sameNumber(a.total, b.total) && a.credit == b.credit && sameRef(a.target, b.target) &&
        sameNumber(a.targetQty, b.targetQty) && sameNumber(a.price, b.price) &&
        sameNumber(a.cost, b.cost) &&
        a.unit == b.unit && a.issues == b.issues

private fun sameLine(a: LineView, b: LineView): Boolean =
    sameRef(a.product, b.product) && sameNumber(a.qty, b.qty) && a.unit == b.unit &&
        sameNumber(a.unitPrice, b.unitPrice) && sameNumber(a.lineTotal, b.lineTotal)

private fun sameNumber(a: BigDecimal?, b: BigDecimal?): Boolean =
    if (a == null || b == null) a == b else a.compareTo(b) == 0

private fun sameRef(a: Ref?, b: Ref?): Boolean =
    if (a is Ref.New && b is Ref.New) looseName(a.name) == looseName(b.name) else a == b

/**
 * Un nombre sin tildes, mayúsculas, plurales simples ni tratamiento: "Doña Tortas de Fresa" y
 * "torta de fresa".
 */
private fun looseName(name: String): List<String> =
    TextNormalizer.normalize(name.replace('.', ' ')).split(' ')
        .filter { it.isNotEmpty() && it !in HONORIFICS }
        .map { it.removeSuffix("s").removeSuffix("e") }

private val HONORIFICS = setOf(
    "don", "dona", "doña", "seu", "senhor", "senhora", "sr", "sra", "mr", "mrs", "ms", "miss",
    "señor", "señora",
)

/**
 * El catálogo de prueba en un idioma, con identificadores numéricos estables por orden y su clave
 * de vuelta.
 */
private class FixtureCatalog(fixture: Map<String, Json>, language: String) {
    private val keyById = mutableMapOf<Long, String>()
    private val unitIds = mutableMapOf<String, Long>()
    private var next = 1L

    val moneyDecimals: Int = (fixture.getValue("money_decimals") as Json.Num).value.toInt()
    val style: NumberStyle = fixture.getValue("number_format").obj().getValue(language).obj().let {
        NumberStyle(it.getValue("decimal").str()[0], it.getValue("grouping").str()[0])
    }

    val catalog: PhraseCatalog

    init {
        val units = fixture.getValue("units").arr().map { json ->
            val unit = json.obj()
            val key = unit.getValue("key").str()
            val id = register(key)
            unitIds[key] = id
            PhraseUnit(
                id = id,
                systemKey = key.takeUnless { (unit["custom"] as? Json.Bool)?.value == true },
                name = unit.getValue("name").obj().getValue(language).str(),
                symbol = unit["symbol"]?.objOrNull()?.get(language)?.strOrNull(),
                kind = UnitKind.valueOf(unit.getValue("kind").str()),
                baseAmountMilli = unit["base_amount"]?.let(::milli),
            )
        }
        val products = fixture.getValue("products").arr().map { json ->
            val product = json.obj()
            PhraseProduct(
                id = register(product.getValue("key").str()),
                name = product.getValue("name").obj().getValue(language).str(),
                baseUnitId = unitIds.getValue(product.getValue("base_unit").str()),
                conversions = product.getValue("conversions").arr().map { conversion ->
                    val c = conversion.obj()
                    PhraseConversion(
                        unitIds.getValue(c.getValue("unit").str()),
                        milli(c.getValue("factor")),
                    )
                },
                forSale = (product.getValue("for_sale") as Json.Bool).value,
                forPurchase = (product.getValue("for_purchase") as Json.Bool).value,
                hasRecipe = (product.getValue("recipe") as Json.Bool).value,
                stockMode = ProductStockMode.valueOf(product.getValue("stock_mode").str()),
            )
        }
        catalog = PhraseCatalog(
            products = products,
            units = units,
            paymentMethods = names(fixture, "payment_methods", language, withSystemKey = true),
            suppliers = names(fixture, "suppliers", language, withSystemKey = false),
            customers = names(fixture, "customers", language, withSystemKey = false),
            expenseCategories = names(
                fixture,
                "expense_categories",
                language,
                withSystemKey = true,
            ),
        )
    }

    private fun register(key: String): Long {
        val id = next++
        keyById[id] = key
        return id
    }

    private fun names(
        fixture: Map<String, Json>,
        list: String,
        language: String,
        withSystemKey: Boolean,
    ): List<PhraseName> = fixture.getValue(list).arr().map { json ->
        val item = json.obj()
        val key = item.getValue("key").str()
        PhraseName(
            register(key),
            key.takeIf {
                withSystemKey
            },
            item.getValue("name").obj().getValue(language).str(),
        )
    }

    private fun milli(json: Json): Long = requireNotNull(Expectation.decimal(json)) {
        "Número ilegible en el catálogo: ${json.render()}"
    }.movePointRight(3).toLong()

    fun describe(reading: PhraseReading): Expectation = Expectation(
        intent = reading.intent?.name,
        reason = reading.reason?.name,
        lines = reading.lines.map { line ->
            LineView(
                product(line.product),
                quantity(line.quantity),
                line.unitId?.let(keyById::getValue),
                money(line.unitPrice),
                money(line.lineTotal),
            )
        },
        payment = name(reading.payment),
        party = name(reading.party),
        category = name(reading.category),
        total = money(reading.total),
        credit = reading.credit,
        target = reading.target?.let(::product),
        targetQty = quantity(reading.targetQuantity),
        price = money(reading.price),
        cost = money(reading.cost),
        unit = reading.unitId?.let(keyById::getValue),
        issues = reading.issues.map { it.name }.toSet(),
    )

    private fun product(match: ProductMatch): Ref = when (match) {
        is ProductMatch.Found -> Ref.Key(keyById.getValue(match.id))
        is ProductMatch.Choose -> Ref.Choose(match.ids.map(keyById::getValue).toSet())
        is ProductMatch.Unknown -> Ref.New(match.spokenName)
    }

    private fun name(match: NameMatch?): Ref? = when (match) {
        null -> null
        is NameMatch.Found -> Ref.Key(keyById.getValue(match.id))
        is NameMatch.Choose -> Ref.Choose(match.ids.map(keyById::getValue).toSet())
        is NameMatch.Unknown -> Ref.New(match.spoken)
    }

    private fun quantity(quantity: QuantityMilli?): BigDecimal? = quantity?.let {
        BigDecimal.valueOf(it.milli, 3)
    }

    private fun money(money: Money?): BigDecimal? = money?.let {
        BigDecimal.valueOf(it.minor, moneyDecimals)
    }
}

private class Report(private val results: List<CaseResult>) {
    private val even = results.filterNot { it.case.isOdd }
    private val odd = results.filter { it.case.isOdd }

    fun summary(): String = "Pares: ${counts(even)}. Impares: ${counts(odd)}."

    private fun counts(items: List<CaseResult>): String = "${items.size} frases, " +
        Verdict.entries.joinToString(", ") { verdict ->
            "${verdict.label} ${items.count { it.verdict == verdict }}"
        }

    fun markdown(): String = buildString {
        appendLine("# Medición del intérprete de frases")
        appendLine()
        appendLine(summary())
        appendLine()
        appendLine("## Totales por paridad del id")
        appendLine()
        appendLine(
            "Los ids pares sirven para ajustar; los impares, la medición final, solo se cuentan.",
        )
        appendLine()
        table(mapOf("pares" to even, "impares" to odd))
        appendLine("## Por idioma y paridad")
        appendLine()
        table(results.groupBy { "${it.language} ${if (it.case.isOdd) "impares" else "pares"}" })
        appendLine("## Pares por etiqueta")
        appendLine()
        table(
            even.flatMap { result ->
                result.case.tags.map { it to result }
            }.groupBy({ it.first }, { it.second }),
        )
        appendLine("## Pares que no salieron correctos")
        appendLine()
        for (result in even.filter {
            it.verdict != Verdict.CORRECT
        }.sortedByDescending { it.verdict.ordinal }) {
            appendLine("### ${result.verdict.label} · ${result.language} · ${result.case.id}")
            appendLine()
            appendLine("> ${result.case.text}")
            appendLine()
            appendLine("Etiquetas: ${result.case.tags.joinToString(", ")}")
            appendLine()
            appendLine("- Esperado: `${result.expected}`")
            appendLine("- Obtenido: `${result.got}`")
            appendLine()
        }
    }

    private fun StringBuilder.table(groups: Map<String, List<CaseResult>>) {
        val header = Verdict.entries.joinToString(" | ") { it.label }
        appendLine("| Grupo | Casos | $header | Bien (CORRECT + ASKED) | Sin registrar (SAFE) |")
        appendLine("|---|---:|" + Verdict.entries.joinToString("") { "---:|" } + "---:|---:|")
        for ((group, items) in groups.toSortedMap()) {
            val counts = Verdict.entries.map { verdict -> items.count { it.verdict == verdict } }
            val good = counts[Verdict.CORRECT.ordinal] + counts[Verdict.ASKED.ordinal]
            val safe = counts[Verdict.SAFE.ordinal]
            val row = counts.joinToString(" | ")
            appendLine(
                "| $group | ${items.size} | $row | ${percent(
                    good,
                    items.size,
                )} | ${percent(safe, items.size)} |",
            )
        }
        appendLine()
    }

    private fun percent(part: Int, whole: Int): String =
        if (whole == 0) "-" else "${part * 100 / whole} %"
}

/** Lo justo de JSON para leer el catálogo y el corpus, sin dependencias. */
private sealed interface Json {
    data class Str(val value: String) : Json

    data class Num(val value: String) : Json

    data class Bool(val value: Boolean) : Json

    data object Null : Json

    data class Arr(val items: List<Json>) : Json

    data class Obj(val fields: Map<String, Json>) : Json

    fun obj(): Map<String, Json> = (this as Obj).fields

    fun objOrNull(): Map<String, Json>? = (this as? Obj)?.fields

    fun arr(): List<Json> = (this as Arr).items

    fun arrOrNull(): List<Json>? = (this as? Arr)?.items

    fun str(): String = (this as Str).value

    fun strOrNull(): String? = (this as? Str)?.value

    fun render(): String = when (this) {
        is Str -> value
        is Num -> value
        is Bool -> value.toString()
        Null -> "null"
        is Arr -> items.joinToString(",", "[", "]") { it.render() }
        is Obj -> fields.entries.joinToString(",", "{", "}") { "${it.key}:${it.value.render()}" }
    }

    companion object {
        fun parse(text: String): Json {
            val parser = JsonParser(text)
            val value = parser.value()
            parser.end()
            return value
        }
    }
}

private class JsonParser(private val text: String) {
    private var index = 0

    fun end() {
        blank()
        check(index == text.length) { "Sobra texto en la posición $index" }
    }

    fun value(): Json {
        blank()
        check(index < text.length) { "El JSON termina antes de tiempo" }
        return when (val char = text[index]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> Json.Str(string())
            't' -> literal("true", Json.Bool(true))
            'f' -> literal("false", Json.Bool(false))
            'n' -> literal("null", Json.Null)
            '-', in '0'..'9' -> number()
            else -> error("Carácter inesperado '$char' en $index")
        }
    }

    private fun obj(): Json {
        index++
        val fields = linkedMapOf<String, Json>()
        blank()
        if (text[index] == '}') return Json.Obj(fields).also { index++ }
        while (true) {
            blank()
            val key = string()
            blank()
            expect(':')
            fields[key] = value()
            blank()
            if (text[index] == ',') index++ else break
        }
        expect('}')
        return Json.Obj(fields)
    }

    private fun arr(): Json {
        index++
        val items = mutableListOf<Json>()
        blank()
        if (text[index] == ']') return Json.Arr(items).also { index++ }
        while (true) {
            items.add(value())
            blank()
            if (text[index] == ',') index++ else break
        }
        expect(']')
        return Json.Arr(items)
    }

    private fun string(): String {
        expect('"')
        val out = StringBuilder()
        while (text[index] != '"') {
            val char = text[index++]
            if (char != '\\') {
                out.append(char)
                continue
            }
            when (val escaped = text[index++]) {
                'n' -> out.append('\n')

                't' -> out.append('\t')

                'r' -> out.append('\r')

                'b' -> out.append('\b')

                'f' -> out.append('\u000C')

                'u' -> {
                    out.append(text.substring(index, index + 4).toInt(16).toChar())
                    index += 4
                }

                else -> out.append(escaped)
            }
        }
        index++
        return out.toString()
    }

    private fun number(): Json {
        val start = index
        while (index < text.length && (text[index].isDigit() || text[index] in "+-.eE")) index++
        return Json.Num(text.substring(start, index))
    }

    private fun literal(word: String, value: Json): Json {
        check(text.startsWith(word, index)) { "Se esperaba $word en $index" }
        index += word.length
        return value
    }

    private fun expect(char: Char) {
        blank()
        check(index < text.length && text[index] == char) { "Se esperaba '$char' en $index" }
        index++
    }

    private fun blank() {
        while (index < text.length && text[index].isWhitespace()) index++
    }
}
