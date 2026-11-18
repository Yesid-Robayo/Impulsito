package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.NewProductTraits
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseCatalog
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLanguage
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseName
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.BREAD
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.COMMA_DECIMAL
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CORN_FLOUR
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.EGG
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.KILOGRAM
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.MARIA
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.POINT_DECIMAL
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.UTILITIES
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.WHEAT_FLOUR
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.money
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Corregir sin borrar: lo dicho con la tarjeta a la vista cambia solo lo que nombra, y lo demás
 * queda. El producto por crear, su tipo y su categoría, la operación y el producto que se le
 * parece a lo dicho.
 */
class PhraseCorrectionTraitsTest {
    private fun PhraseDraft.fix(
        text: String,
        catalog: PhraseCatalog = PhraseCatalogs.spanish,
    ): PhraseDraft? = correctedBy(text, PhraseLanguage.ES, COMMA_DECIMAL, 2, catalog)

    private fun PhraseDraft.english(text: String): PhraseDraft? =
        correctedBy(text, PhraseLanguage.EN, POINT_DECIMAL, 2, PhraseCatalogs.english)

    private fun PhraseDraft.portuguese(text: String): PhraseDraft? =
        correctedBy(text, PhraseLanguage.PT, COMMA_DECIMAL, 2, PhraseCatalogs.portuguese)

    private fun item(id: Long) = DraftTarget.Existing(id)

    private fun fresh(name: String, traits: NewProductTraits = NewProductTraits.NONE) =
        DraftTarget.New(name, traits)

    private fun line(
        product: DraftTarget?,
        quantity: QuantityMilli? = null,
        unit: Long? = null,
        unitPrice: Money? = null,
    ) = DraftLine(product, quantity, unit, unitPrice)

    private fun sale(vararg lines: DraftLine) =
        PhraseDraft(PhraseIntent.SALE, lines = lines.toList())

    private fun purchase(vararg lines: DraftLine) =
        PhraseDraft(PhraseIntent.PURCHASE, lines = lines.toList())

    private val input = NewProductTraits(usage = ProductUsage.INPUT)
    private val flour = line(fresh("Harina"), units(2), KILOGRAM)
    private val bread = line(item(BREAD), units(2))
    private val egg = line(item(EGG), units(1))

    private val withCategories = PhraseCatalogs.spanish.copy(
        productCategories = listOf(PhraseName(MATERIALS, null, "Materia prima")),
    )

    // El nombre del producto por crear.

    @Test
    fun `a new name goes to the product to be created and keeps what was said of it`() {
        assertEquals(
            purchase(line(fresh("Harina integral", input), units(2), KILOGRAM)),
            purchase(line(fresh("Harina", input), units(2), KILOGRAM))
                .fix("se llama harina integral"),
        )
        // Con varias líneas, va a la última por crear, y las demás quedan.
        assertEquals(
            purchase(line(fresh("Harina integral"), units(2), KILOGRAM), bread),
            purchase(flour, bread).fix("se llama harina integral"),
        )
        val product = PhraseDraft(PhraseIntent.NEW_PRODUCT, target = fresh("Vela"))
        assertEquals(
            product.copy(target = fresh("Vela de canela")),
            product.fix("se llama vela de canela"),
        )
    }

    @Test
    fun `a new name without a name or without a product to create is not understood`() {
        assertNull(purchase(flour).fix("se llama"))
        assertNull(sale(bread).fix("se llama pan dulce"))
    }

    @Test
    fun `a longer name said alone renames the product to be created`() {
        assertEquals(
            purchase(line(fresh("Harina integral", input), units(2), KILOGRAM)),
            purchase(line(fresh("Harina", input), units(2), KILOGRAM)).fix("no, harina integral"),
        )
        // Un nombre que no lleva el que tenía no se entiende: podría ser otro producto.
        assertNull(sale(line(fresh("Torta"), units(1))).fix("era de arroz"))
    }

    // Lo que es el producto por crear.

    @Test
    fun `the type said goes to the product named, and the rest stays`() {
        assertEquals(
            purchase(line(fresh("Harina", input), units(2), KILOGRAM)),
            purchase(flour).fix("la harina es insumo"),
        )
        // Sin nombrarlo, va al último producto por crear.
        assertEquals(
            purchase(flour, line(fresh("Avena", input), units(3), KILOGRAM)),
            purchase(flour, line(fresh("Avena"), units(3), KILOGRAM)).fix("es materia prima"),
        )
        assertEquals(
            purchase(line(fresh("Harina", input), units(2), KILOGRAM)),
            purchase(flour).fix("y es un insumo"),
        )
    }

    @Test
    fun `a type for a product that is not to be created is not understood`() {
        assertNull(sale(bread).fix("es un insumo"))
        assertNull(purchase(flour).fix("el arroz es insumo"))
    }

    @Test
    fun `a type and a quantity said together change both`() {
        assertEquals(
            purchase(line(fresh("Harina", input), units(3), KILOGRAM)),
            purchase(flour).fix("la harina es insumo, son 3 kilos"),
        )
    }

    @Test
    fun `a type said with a new line goes to the new product it adds`() {
        assertEquals(
            purchase(flour, line(fresh("Avena", input), units(2), KILOGRAM)),
            purchase(flour).fix("agrega 2 kilos de avena, es materia prima"),
        )
        // Una línea agregada que no se entiende no se agrega, ni con su descripción.
        assertNull(purchase(flour).fix("agrega unos panes, es un insumo"))
        // Lo que ya existe no cambia: la descripción no tiene a quién ir.
        assertEquals(
            purchase(flour, line(item(EGG), units(2))),
            purchase(flour).fix("agrega 2 huevos, es un insumo"),
        )
    }

    @Test
    fun `the type and the category of a new product change together`() {
        val product = PhraseDraft(PhraseIntent.NEW_PRODUCT, target = fresh("Vela"))
        assertEquals(
            product.copy(target = fresh("Vela", input.copy(categoryId = MATERIALS))),
            product.fix("es un insumo, categoría materias primas", withCategories),
        )
    }

    // La operación.

    @Test
    fun `a sale that was a purchase keeps its lines and drops the customer`() {
        assertEquals(
            purchase(bread),
            sale(bread).copy(partyId = MARIA).fix("no, fue una compra"),
        )
        assertEquals(purchase(bread), sale(bread).fix("no, compré"))
        assertEquals(
            purchase(line(item(BREAD), units(3))),
            sale(bread).fix("fue una compra, eran 3"),
        )
        assertEquals(purchase(egg), sale(egg).english("no, it was a purchase"))
        assertEquals(purchase(egg), sale(egg).portuguese("não, foi uma compra"))
    }

    @Test
    fun `the same operation changes nothing, and other operations do not switch`() {
        assertEquals(sale(bread), sale(bread).fix("fue una venta"))
        val expense = PhraseDraft(
            PhraseIntent.EXPENSE,
            categoryId = UTILITIES,
            total = money(500),
        )
        assertNull(expense.fix("fue una compra"))
        // En un ajuste, el verbo de otra operación se salta, como siempre.
        val damaged = PhraseDraft(PhraseIntent.STOCK_ADJUSTMENT, lines = listOf(bread))
        assertEquals(
            damaged.copy(lines = listOf(line(item(BREAD), units(4)))),
            damaged.fix("no, compré 4"),
        )
    }

    @Test
    fun `after a no, the verb and one product correct that line`() {
        assertEquals(sale(line(item(BREAD), units(3))), sale(bread).fix("no, vendí 3 panes"))
        assertNull(sale(bread).fix("no, vendí 3 panes y 2 gaseosas"))
    }

    // El producto que se le parece a lo dicho.

    @Test
    fun `a product said alone goes to the line it resembles, not to the last one`() {
        assertEquals(
            purchase(line(item(WHEAT_FLOUR), units(2), KILOGRAM), egg),
            purchase(flour, egg).fix("no, la harina de trigo"),
        )
        assertEquals(
            purchase(line(item(CORN_FLOUR), units(1), KILOGRAM), egg),
            purchase(line(null, units(1), KILOGRAM), egg).fix("no, harina de maíz"),
        )
        assertEquals(
            sale(line(item(WHEAT_FLOUR), units(1), KILOGRAM), egg),
            sale(line(item(CORN_FLOUR), units(1), KILOGRAM), egg).fix("no, harina de trigo"),
        )
        // Si ninguna se le parece, va a la última, como siempre.
        assertEquals(
            sale(line(item(9_999L), units(2)), line(item(CORN_FLOUR), units(1))),
            sale(line(item(9_999L), units(2)), egg).fix("no, harina de maíz"),
        )
    }

    private companion object {
        const val MATERIALS = 600L
    }
}
