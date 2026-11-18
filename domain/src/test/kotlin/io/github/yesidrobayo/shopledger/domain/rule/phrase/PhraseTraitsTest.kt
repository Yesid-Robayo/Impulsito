package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.NameMatch
import io.github.yesidrobayo.shopledger.domain.model.phrase.NewProductTraits
import io.github.yesidrobayo.shopledger.domain.model.phrase.NumberStyle
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseCatalog
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIssue
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLanguage
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseName
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseReading
import io.github.yesidrobayo.shopledger.domain.model.phrase.ProductMatch
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.COMMA_DECIMAL
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CORN_FLOUR
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.KILOGRAM
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.RENT
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.WHEAT_FLOUR
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.money
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.spanish
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.units
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lo que se dice de un producto nuevo dentro de la frase ("esto es un insumo", "en la categoría
 * materias primas"), que nunca se vuelve un producto más, y los números agrupados con espacio.
 */
class PhraseTraitsTest {
    private fun line(
        product: ProductMatch,
        quantity: QuantityMilli? = null,
        unit: Long? = null,
        unitPrice: Money? = null,
        lineTotal: Money? = null,
    ) = PhraseLine(product, quantity, unit, unitPrice, lineTotal)

    private fun fresh(name: String, traits: NewProductTraits = NewProductTraits.NONE) =
        ProductMatch.Unknown(name, traits)

    private val input = NewProductTraits(usage = ProductUsage.INPUT)

    /** El catálogo de prueba sin harinas, con dos categorías de producto. */
    private val described: PhraseCatalog = PhraseCatalogs.spanish.copy(
        products = PhraseCatalogs.spanish.products.filterNot {
            it.id == WHEAT_FLOUR || it.id == CORN_FLOUR
        },
        productCategories = listOf(
            PhraseName(MATERIALS, null, "Materia prima"),
            PhraseName(DRINKS, null, "Bebidas"),
        ),
    )

    // Lo que se dice de un producto nuevo.

    @Test
    fun `the type and the category of a new product go to it, not to a new line`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.PURCHASE,
                lines = listOf(
                    line(
                        fresh("Harina", input.copy(categoryId = MATERIALS)),
                        units(2),
                        KILOGRAM,
                        unitPrice = money(50_000),
                    ),
                ),
            ),
            spanish(
                "compré 2 kilos de harina a 50 mil esto es un insumo en la categoría " +
                    "materias primas",
                described,
            ),
        )
    }

    @Test
    fun `a category that does not exist stays as said, to be created`() {
        val noMaterials = described.copy(productCategories = listOf(PhraseName(DRINKS, null, "B")))
        assertEquals(
            listOf(
                line(
                    fresh("Harina", input.copy(newCategory = "Materias primas")),
                    units(2),
                    KILOGRAM,
                    unitPrice = money(50_000),
                ),
            ),
            spanish(
                "compré 2 kilos de harina a 50 mil esto es un insumo en la categoría " +
                    "materias primas",
                noMaterials,
            ).lines,
        )
    }

    @Test
    fun `what is said before the product goes to the first new one after it`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.PURCHASE,
                lines = listOf(line(fresh("Harina", input), units(2), KILOGRAM)),
            ),
            spanish("es un insumo, compré 2 kilos de harina", described),
        )
    }

    @Test
    fun `each description goes to the new product said just before it`() {
        assertEquals(
            listOf(
                line(
                    fresh("Arroz", NewProductTraits(usage = ProductUsage.SALE)),
                    units(2),
                    KILOGRAM,
                ),
                line(fresh("Avena", input), units(3), KILOGRAM),
            ),
            spanish(
                "compré 2 kilos de arroz, es para vender, y 3 kilos de avena, es materia prima",
                described,
            ).lines,
        )
    }

    @Test
    fun `a description of a product that exists is dropped, and makes no line`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.PURCHASE,
                lines = listOf(line(ProductMatch.Found(WHEAT_FLOUR), units(2), KILOGRAM)),
            ),
            spanish("compré 2 kilos de harina de trigo, es un insumo"),
        )
        assertEquals(
            listOf(
                line(ProductMatch.Found(WHEAT_FLOUR), units(2), KILOGRAM),
                line(fresh("Avena", input), units(3), KILOGRAM),
            ),
            spanish("compré 2 kilos de harina de trigo y 3 kilos de avena, es materia prima").lines,
        )
    }

    @Test
    fun `a service is sold and has no stock`() {
        val service = NewProductTraits(
            usage = ProductUsage.SALE,
            stockMode = ProductStockMode.UNTRACKED,
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.SALE,
                lines = listOf(line(fresh("Asesoría", service), units(1))),
            ),
            spanish("vendí una asesoría, es un servicio", described),
        )
    }

    @Test
    fun `a new product takes everything said of it`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.NEW_PRODUCT,
                target = fresh("Vela de canela", input.copy(categoryId = MATERIALS)),
            ),
            spanish(
                "nuevo producto vela de canela, es un insumo, categoría materias primas",
                described,
            ),
        )
    }

    @Test
    fun `a description in another operation is read as it always was`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.EXPENSE,
                category = NameMatch.Found(RENT),
                total = money(500),
            ),
            spanish("pagué el arriendo 500, es un servicio"),
        )
    }

    @Test
    fun `a description alone says nothing to register`() {
        assertEquals(
            PhraseReading(intent = null, issues = setOf(PhraseIssue.NOTHING_UNDERSTOOD)),
            spanish("es un insumo"),
        )
    }

    // Lo que no se entiende no se vuelve un producto.

    @Test
    fun `words that are no product are left out and the card says so`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.PURCHASE,
                lines = listOf(line(ProductMatch.Found(WHEAT_FLOUR), units(2), KILOGRAM)),
                issues = setOf(PhraseIssue.UNUSED_WORDS),
            ),
            spanish("compré 2 kilos de harina de trigo y esto es algo raro"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.PURCHASE,
                lines = listOf(line(ProductMatch.Found(WHEAT_FLOUR))),
                issues = setOf(PhraseIssue.UNUSED_WORDS),
            ),
            spanish("compré harina de trigo esto es algo"),
        )
        // Una apertura sin tipo detrás no describe nada, y tampoco es un producto.
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.PURCHASE,
                lines = listOf(line(ProductMatch.Found(WHEAT_FLOUR))),
                issues = setOf(PhraseIssue.UNUSED_WORDS),
            ),
            spanish("compré harina de trigo, es"),
        )
    }

    // Sin verbo, la operación es una suposición.

    @Test
    fun `without a verb a name that fits several products asks which one`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.SALE,
                lines = listOf(
                    line(
                        ProductMatch.Choose(listOf(WHEAT_FLOUR, CORN_FLOUR)),
                        units(2),
                        KILOGRAM,
                        unitPrice = money(50_000),
                    ),
                ),
            ),
            spanish("2 kilos de harina a 50000"),
        )
        // Con el verbo, en una venta solo cuenta lo que se vende: la de maíz.
        assertEquals(
            listOf(
                line(ProductMatch.Found(CORN_FLOUR), units(2), KILOGRAM, unitPrice = money(50_000)),
            ),
            spanish("vendí 2 kilos de harina a 50000").lines,
        )
    }

    // Números agrupados con espacio.

    @Test
    fun `an amount after its marker joins groups of three figures`() {
        assertEquals(
            listOf(
                line(
                    ProductMatch.Found(WHEAT_FLOUR),
                    units(2),
                    KILOGRAM,
                    unitPrice = money(50_000),
                ),
            ),
            spanish("compré 2 kilos de harina de trigo a 50 000").lines,
        )
    }

    private fun number(
        text: String,
        amount: Boolean = false,
        style: NumberStyle = COMMA_DECIMAL,
        start: Int = 0,
    ): NumberRead? = NumberReader(PhraseLexicons.of(PhraseLanguage.ES), style)
        .read(PhraseTokenizer.tokenize(text), start, amount)

    @Test
    fun `spaced groups always join, as dictation writes thousands`() {
        // Así escribe el dictado los miles: "tres a quinientos" se dicta con su "a".
        assertEquals(NumberRead(3_500_000L, 2, money = false), number("3 500"))
        assertEquals(NumberRead(200_000_000L, 2, money = false), number("200\u00A0000"))
        assertEquals(NumberRead(200_000_000L, 2, money = false), number("200\u202F000"))
        assertEquals(NumberRead(2_300_000L, 2, money = false), number("2 300 gramos"))
        assertEquals(NumberRead(3_500_000L, 2, money = false), number("3 500", amount = true))
        assertEquals(
            NumberRead(3_500_000L, 2, money = false),
            number("3 500", style = NumberStyle(decimal = ',', grouping = ' ')),
        )
        assertEquals(
            NumberRead(3_500_000L, 2, money = false),
            number("3 500", style = NumberStyle(decimal = ',', grouping = ' ')),
        )
        assertEquals(
            NumberRead(1_500_000_000L, 4, money = true),
            number("1 500 000 pesos", amount = true),
        )
        assertEquals(NumberRead(1_500_000L, 2, money = false), number("1 500 kilos", amount = true))
    }

    @Test
    fun `what is not a spaced group is read as always`() {
        assertEquals(NumberRead(1_500_000L, 1, money = false), number("1500 000", amount = true))
        assertEquals(NumberRead(2_000L, 1, money = false), number("dos 000", amount = true))
        assertEquals(NumberRead(50_000L, 1, money = false), number("50 kilos", amount = true))
        // Más cifras de las que lee un número: queda el primer grupo.
        assertEquals(
            NumberRead(1_000L, 1, money = false),
            number("1 000 000 000 000", amount = true),
        )
        assertEquals(null, number("a", amount = true, start = 1))
    }

    // Quién dice la operación.

    @Test
    fun `only a verb says which operation it is`() {
        assertEquals(true, PhraseInterpreter.statesOperation("vendí 2 panes", PhraseLanguage.ES))
        assertEquals(true, PhraseInterpreter.statesOperation("el pan vale 600", PhraseLanguage.ES))
        assertEquals(
            false,
            PhraseInterpreter.statesOperation("la harina es insumo", PhraseLanguage.ES),
        )
        assertEquals(false, PhraseInterpreter.statesOperation("recibí 3", PhraseLanguage.ES))
    }

    // La tarjeta.

    @Test
    fun `the card keeps what was said, and a purchase never takes a service`() {
        val service = NewProductTraits(
            usage = ProductUsage.SALE,
            stockMode = ProductStockMode.UNTRACKED,
            newCategory = "Asesorías",
        )
        val said = listOf(line(fresh("Asesoría", service), units(1)))
        assertEquals(
            listOf(DraftLine(DraftTarget.New("Asesoría", service), units(1), null)),
            PhraseReading(intent = PhraseIntent.SALE, lines = said).toDraft()?.lines,
        )
        assertEquals(
            listOf(
                DraftLine(
                    DraftTarget.New(
                        "Asesoría",
                        NewProductTraits(usage = ProductUsage.SALE, newCategory = "Asesorías"),
                    ),
                    units(1),
                    null,
                ),
            ),
            PhraseReading(intent = PhraseIntent.PURCHASE, lines = said).toDraft()?.lines,
        )
    }

    @Test
    fun `new traits replace only what they say`() {
        val all = NewProductTraits(
            usage = ProductUsage.INPUT,
            stockMode = ProductStockMode.UNTRACKED,
            categoryId = 1L,
        )
        assertEquals(all, all.with(NewProductTraits.NONE))
        assertEquals(
            NewProductTraits(
                usage = ProductUsage.BOTH,
                stockMode = ProductStockMode.UNTRACKED,
                newCategory = "Nueva",
            ),
            all.with(NewProductTraits(usage = ProductUsage.BOTH, newCategory = "Nueva")),
        )
        assertEquals(
            NewProductTraits(usage = ProductUsage.INPUT, categoryId = 2L),
            NewProductTraits(usage = ProductUsage.INPUT, newCategory = "Vieja")
                .with(NewProductTraits(categoryId = 2L)),
        )
    }

    @Test
    fun `a category can follow the opening of a description`() {
        assertEquals(
            listOf(line(fresh("Arroz", NewProductTraits(categoryId = DRINKS)), units(2), KILOGRAM)),
            spanish("compré 2 kilos de arroz, es de la categoría bebidas", described).lines,
        )
    }

    // El lector de descripciones, por separado.

    private fun scan(
        text: String,
        language: PhraseLanguage = PhraseLanguage.ES,
        categories: List<PhraseName> = emptyList(),
    ): Pair<List<String>, List<PlacedTrait>> {
        val lexicon = PhraseLexicons.of(language)
        val numbers = NumberReader(lexicon, COMMA_DECIMAL)
        val shaper = WordShaper(lexicon, numbers, UnitIndex(emptyList(), lexicon))
        val scan = TraitReader(lexicon, numbers, shaper, categories)
            .scan(PhraseTokenizer.tokenize(text))
        return scan.rest.map { it.norm } to scan.traits
    }

    private fun named(position: Int, name: String) =
        PlacedTrait(position, NewProductTraits(newCategory = name))

    @Test
    fun `the name of a category ends where something else starts`() {
        assertEquals(
            listOf(",", "pan") to listOf(named(0, "Bebidas")),
            scan("categoría bebidas, pan"),
        )
        assertEquals(
            listOf("3", "panes") to listOf(named(0, "Bebidas")),
            scan("categoría bebidas 3 panes"),
        )
        assertEquals(
            listOf("en", "efectivo") to listOf(named(0, "Bebidas")),
            scan("categoría bebidas en efectivo"),
        )
        assertEquals(
            emptyList<String>() to listOf(named(0, "Bebidas"), PlacedTrait(0, input)),
            scan("categoría bebidas esto es un insumo"),
        )
        assertEquals(
            emptyList<String>() to listOf(named(0, "Bebidas"), named(0, "Frutas")),
            scan("categoría bebidas categoría frutas"),
        )
        // Cuatro palabras como mucho.
        assertEquals(
            listOf("nueces") to listOf(named(0, "Frutas verduras granos semillas")),
            scan("categoría frutas verduras granos semillas nueces"),
        )
    }

    @Test
    fun `a category said loosely keeps only its name, or nothing`() {
        assertEquals(
            emptyList<String>() to listOf(named(0, "Bebidas")),
            scan("categoría de las bebidas"),
        )
        assertEquals(
            emptyList<String>() to listOf(named(0, "Bebidas")),
            scan("categoría bebidas de"),
        )
        assertEquals(
            emptyList<String>() to listOf(PlacedTrait(0, NewProductTraits.NONE)),
            scan("en la categoría"),
        )
        // Si cabe en varias, no se elige ninguna: queda la dicha, que se crea con el producto.
        assertEquals(
            emptyList<String>() to listOf(named(0, "Bebidas")),
            scan(
                "categoría bebidas",
                categories = listOf(
                    PhraseName(COLD, null, "Bebidas frías"),
                    PhraseName(HOT, null, "Bebidas calientes"),
                ),
            ),
        )
    }

    @Test
    fun `a description never splits a verb, but may hold a whole one`() {
        // "to sell" es parte de "I'm going to sell", que crea un producto.
        assertEquals(
            listOf("im", "going", "to", "sell", "cookies") to emptyList<PlacedTrait>(),
            scan("I'm going to sell cookies", PhraseLanguage.EN),
        )
        // "venta" va entera dentro de "para la venta"; "venta de" no.
        assertEquals(
            listOf("compre", "harina") to
                listOf(PlacedTrait(2, NewProductTraits(usage = ProductUsage.SALE))),
            scan("compré harina para la venta"),
        )
        assertEquals(
            listOf("para", "la", "venta", "de", "pan") to emptyList<PlacedTrait>(),
            scan("para la venta de pan"),
        )
    }

    @Test
    fun `in english the name of a category may go before the word category`() {
        val raw = listOf(PhraseName(MATERIALS, null, "Raw material"))
        assertEquals(
            emptyList<String>() to listOf(PlacedTrait(0, NewProductTraits(categoryId = MATERIALS))),
            scan("in the raw materials category", PhraseLanguage.EN, raw),
        )
        // Solo una parte da con una categoría: queda todo lo dicho, para crearla.
        assertEquals(
            emptyList<String>() to listOf(named(0, "Fresh drinks")),
            scan(
                "in the fresh drinks category",
                PhraseLanguage.EN,
                listOf(PhraseName(FRESH, null, "Fresh")),
            ),
        )
        assertEquals(
            listOf("from") to listOf(PlacedTrait(1, NewProductTraits.NONE)),
            scan("from the category", PhraseLanguage.EN),
        )
        assertEquals(
            listOf("in", "cash") to emptyList<PlacedTrait>(),
            scan("in cash", PhraseLanguage.EN),
        )
    }

    private companion object {
        const val MATERIALS = 600L
        const val DRINKS = 601L
        const val COLD = 602L
        const val HOT = 603L
        const val FRESH = 604L
    }
}
