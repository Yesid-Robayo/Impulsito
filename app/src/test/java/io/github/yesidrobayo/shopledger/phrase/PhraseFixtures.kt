package io.github.yesidrobayo.shopledger.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.phrase.NameMatch
import io.github.yesidrobayo.shopledger.domain.model.phrase.NewProductTraits
import io.github.yesidrobayo.shopledger.domain.model.phrase.NumberStyle
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseCatalog
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseConversion
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIssue
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseName
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseProduct
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseReading
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseUnit
import io.github.yesidrobayo.shopledger.domain.model.phrase.ProductMatch
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import java.math.BigDecimal

// Piezas comunes de las mediciones del intérprete: el catálogo de prueba, lo esperado en los
// términos del corpus y lo justo de JSON para leerlos.

/** Una referencia a algo del catálogo, tal como la escribe el corpus. */
internal sealed interface Ref {
    data class Key(val key: String) : Ref

    data class Choose(val keys: Set<String>) : Ref

    /**
     * Un producto o un nombre que no existe, con lo que se dijo de él si es un producto: su uso, su
     * modo de stock, la categoría del catálogo por su clave o la categoría nueva por su nombre.
     */
    data class New(
        val name: String,
        val usage: String? = null,
        val stockMode: String? = null,
        val category: String? = null,
        val newCategory: String? = null,
    ) : Ref

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
                    New(
                        name = obj["new"]?.strOrNull().orEmpty(),
                        usage = obj["usage"]?.strOrNull(),
                        stockMode = obj["stock_mode"]?.strOrNull(),
                        category = obj["category"]?.strOrNull(),
                        newCategory = obj["new_category"]?.strOrNull(),
                    )
                }
            }
        }
    }
}

internal data class LineView(
    val product: Ref?,
    val qty: BigDecimal?,
    val unit: String?,
    val unitPrice: BigDecimal?,
    val lineTotal: BigDecimal?,
)

/** Una lectura en los términos del corpus: claves del catálogo y números decimales. */
internal data class Expectation(
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

internal fun same(a: Expectation, b: Expectation): Boolean =
    a.reason == b.reason && a.lines.size == b.lines.size &&
        a.lines.zip(b.lines).all { (x, y) -> sameLine(x, y) } &&
        sameRef(a.payment, b.payment) && sameRef(a.party, b.party) &&
        sameRef(a.category, b.category) &&
        sameNumber(a.total, b.total) && a.credit == b.credit && sameRef(a.target, b.target) &&
        sameNumber(a.targetQty, b.targetQty) && sameNumber(a.price, b.price) &&
        sameNumber(a.cost, b.cost) &&
        a.unit == b.unit && a.issues == b.issues

internal fun sameLine(a: LineView, b: LineView): Boolean =
    sameRef(a.product, b.product) && sameNumber(a.qty, b.qty) && a.unit == b.unit &&
        sameNumber(a.unitPrice, b.unitPrice) && sameNumber(a.lineTotal, b.lineTotal)

internal fun sameNumber(a: BigDecimal?, b: BigDecimal?): Boolean =
    if (a == null || b == null) a == b else a.compareTo(b) == 0

internal fun sameRef(a: Ref?, b: Ref?): Boolean = if (a is Ref.New && b is Ref.New) {
    looseName(a.name) == looseName(b.name) && a.usage == b.usage &&
        a.stockMode == b.stockMode && a.category == b.category &&
        a.newCategory?.let(::looseName) == b.newCategory?.let(::looseName)
} else {
    a == b
}

/**
 * Un nombre sin tildes, mayúsculas, plurales simples ni tratamiento: "Doña Tortas de Fresa" y
 * "torta de fresa".
 */
internal fun looseName(name: String): List<String> =
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
internal class FixtureCatalog(fixture: Map<String, Json>, language: String) {
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
            productCategories = names(
                fixture,
                "product_categories",
                language,
                withSystemKey = false,
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

        is ProductMatch.Unknown -> Ref.New(
            name = match.spokenName,
            usage = match.traits.usage?.name,
            stockMode = match.traits.stockMode?.name,
            category = match.traits.categoryId?.let(keyById::getValue),
            newCategory = match.traits.newCategory,
        )
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

    /** La clave del catálogo de un id, o el id si no es de ninguno, para mostrarlo. */
    fun keyOf(id: Long): String = keyById[id] ?: "#$id"

    /**
     * La lectura que describe [expected], con los ids de este catálogo: así una prueba arma la
     * tarjeta de lo esperado sin depender de que el intérprete lea bien una frase.
     */
    fun reading(expected: Expectation): PhraseReading {
        val intent = expected.intent?.let(PhraseIntent::valueOf)
        val customerSide = intent == PhraseIntent.SALE || intent == PhraseIntent.CUSTOMER_PAYMENT
        val people = if (customerSide) catalog.customers else catalog.suppliers
        return PhraseReading(
            intent = intent,
            reason = expected.reason?.let(StockAdjustmentReason::valueOf),
            lines = expected.lines.map { line ->
                PhraseLine(
                    product = productMatch(requireNotNull(line.product) { "Línea sin producto" }),
                    quantity = line.qty?.let { QuantityMilli(it.movePointRight(3).toLong()) },
                    unitId = line.unit?.let { idIn(it, catalog.units.map(PhraseUnit::id)) },
                    unitPrice = line.unitPrice?.let(::moneyOf),
                    lineTotal = line.lineTotal?.let(::moneyOf),
                )
            },
            payment = expected.payment?.let { nameMatch(it, catalog.paymentMethods) },
            party = expected.party?.let { nameMatch(it, people) },
            category = expected.category?.let { nameMatch(it, catalog.expenseCategories) },
            total = expected.total?.let(::moneyOf),
            credit = expected.credit,
            target = expected.target?.let(::productMatch),
            targetQuantity = expected.targetQty?.let {
                QuantityMilli(it.movePointRight(3).toLong())
            },
            price = expected.price?.let(::moneyOf),
            cost = expected.cost?.let(::moneyOf),
            unitId = expected.unit?.let { idIn(it, catalog.units.map(PhraseUnit::id)) },
            issues = expected.issues.map(PhraseIssue::valueOf).toSet(),
        )
    }

    /** La categoría de producto que se llama [name], sin tildes ni plurales, si hay una. */
    fun productCategoryNamed(name: String): Long? =
        catalog.productCategories.firstOrNull { looseName(it.name) == looseName(name) }?.id

    private fun productMatch(ref: Ref): ProductMatch {
        val ids = catalog.products.map(PhraseProduct::id)
        return when (ref) {
            is Ref.Key -> ProductMatch.Found(idIn(ref.key, ids))

            is Ref.Choose -> ProductMatch.Choose(ref.keys.map { idIn(it, ids) })

            is Ref.New -> ProductMatch.Unknown(
                ref.name,
                NewProductTraits(
                    usage = ref.usage?.let(ProductUsage::valueOf),
                    stockMode = ref.stockMode?.let(ProductStockMode::valueOf),
                    categoryId = ref.category?.let {
                        idIn(it, catalog.productCategories.map(PhraseName::id))
                    },
                    newCategory = ref.newCategory,
                ),
            )
        }
    }

    private fun nameMatch(ref: Ref, pool: List<PhraseName>): NameMatch {
        val ids = pool.map(PhraseName::id)
        return when (ref) {
            is Ref.Key -> NameMatch.Found(idIn(ref.key, ids))
            is Ref.Choose -> NameMatch.Choose(ref.keys.map { idIn(it, ids) })
            is Ref.New -> NameMatch.Unknown(ref.name)
        }
    }

    private fun idIn(key: String, pool: List<Long>): Long =
        requireNotNull(pool.firstOrNull { keyById[it] == key }) { "Clave desconocida: $key" }

    private fun moneyOf(amount: BigDecimal): Money =
        Money(amount.movePointRight(moneyDecimals).toLong())
}

/** Lo justo de JSON para leer el catálogo y el corpus, sin dependencias. */
internal sealed interface Json {
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

internal class JsonParser(private val text: String) {
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
