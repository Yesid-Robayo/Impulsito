package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLanguage
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.BREAD
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CARD
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CASH
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.COMMA_DECIMAL
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CORN_FLOUR
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.EGG
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.KILOGRAM
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.MARIA
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.ON_ACCOUNT
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.ORANGE_JUICE
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.POINT_DECIMAL
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.SODA_BIG
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.SODA_SMALL
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.UTILITIES
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.WHEAT_FLOUR
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.WHOLE_BREAD
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.money
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Las correcciones que enseñó la guía de conversación: elegir entre los productos por los que se
 * preguntó, el complemento solo, el pago repartido, el crédito con la persona dentro, el motivo
 * de un ajuste y los miles que se dan por dichos.
 */
class PhraseCorrectionGuideTest {
    private fun PhraseDraft.fix(text: String): PhraseDraft? =
        correctedBy(text, PhraseLanguage.ES, COMMA_DECIMAL, 2, PhraseCatalogs.spanish)

    private fun PhraseDraft.fixInEnglish(text: String): PhraseDraft? =
        correctedBy(text, PhraseLanguage.EN, POINT_DECIMAL, 2, PhraseCatalogs.english)

    private fun item(id: Long) = DraftTarget.Existing(id)

    private fun hole(quantity: QuantityMilli, unit: Long?, vararg candidates: Long) =
        DraftLine(null, quantity, unit, candidates = candidates.toList())

    @Test
    fun `a size or a complement chooses among the asked products`() {
        val sodas = PhraseDraft(
            PhraseIntent.PURCHASE,
            lines = listOf(hole(units(2), PhraseCatalogs.BOX, SODA_SMALL, SODA_BIG)),
        )
        assertEquals(
            sodas.copy(lines = listOf(DraftLine(item(SODA_BIG), units(2), PhraseCatalogs.BOX))),
            sodas.fix("las grandes"),
        )
        val flours = PhraseDraft(
            PhraseIntent.PURCHASE,
            lines = listOf(hole(units(2), KILOGRAM, WHEAT_FLOUR, CORN_FLOUR)),
        )
        assertEquals(
            flours.copy(lines = listOf(DraftLine(item(CORN_FLOUR), units(2), KILOGRAM))),
            flours.fix("no, de maíz"),
        )
        // Un tamaño que no elige a uno, como "medianas", no se adivina.
        assertNull(sodas.fix("las medianas"))
    }

    @Test
    fun `a split payment leaves the method to choose`() {
        val sale = PhraseDraft(
            PhraseIntent.SALE,
            lines = listOf(DraftLine(item(BREAD), units(2), null)),
            paymentMethodId = CASH,
        )
        assertEquals(
            sale.copy(paymentMethodId = null),
            sale.fix("mitad en efectivo y mitad con tarjeta"),
        )
        assertEquals(sale.copy(paymentMethodId = CARD), sale.fix("no, con tarjeta"))
    }

    @Test
    fun `a credit form with the person inside, and pronouns that say credit`() {
        val sale = PhraseDraft(
            PhraseIntent.SALE,
            lines = listOf(DraftLine(item(BREAD), units(2), null)),
        )
        assertEquals(
            sale.copy(partyId = MARIA, paymentMethodId = ON_ACCOUNT, credit = true),
            sale.fixInEnglish("on Mary Smith's tab"),
        )
        val purchase = PhraseDraft(
            PhraseIntent.PURCHASE,
            lines = listOf(DraftLine(item(WHEAT_FLOUR), units(1), KILOGRAM)),
        )
        assertEquals(
            purchase.copy(paymentMethodId = ON_ACCOUNT, credit = true),
            purchase.fixInEnglish("I still owe it"),
        )
    }

    @Test
    fun `the reason of an adjustment can be corrected`() {
        val damaged = PhraseDraft(
            PhraseIntent.STOCK_ADJUSTMENT,
            reason = StockAdjustmentReason.DAMAGED,
            lines = listOf(DraftLine(item(EGG), units(3), null)),
        )
        assertEquals(
            damaged.copy(reason = StockAdjustmentReason.EXPIRED),
            damaged.fix("no, se vencieron"),
        )
        assertEquals(
            damaged.copy(
                reason = StockAdjustmentReason.LOST_OR_STOLEN,
                lines = listOf(DraftLine(item(EGG), units(4), null)),
            ),
            damaged.fix("no, se perdieron 4"),
        )
        // El mismo motivo no cambia nada que no se haya dicho.
        assertNull(damaged.fix("se dañaron"))
    }

    @Test
    fun `a removal finds the line by the words of its name`() {
        val sale = PhraseDraft(
            PhraseIntent.SALE,
            lines = listOf(
                DraftLine(item(BREAD), units(2), null),
                DraftLine(item(ORANGE_JUICE), units(1), null),
            ),
        )
        assertEquals(
            sale.copy(lines = listOf(DraftLine(item(BREAD), units(2), null))),
            sale.fix("quita la naranja"),
        )
    }

    @Test
    fun `a price change keeps its amount when its product is corrected`() {
        val price = PhraseDraft(
            PhraseIntent.PRICE_CHANGE,
            lines = listOf(DraftLine(item(BREAD), null, null, unitPrice = money(600))),
        )
        assertEquals(
            price.copy(
                lines = listOf(DraftLine(item(WHOLE_BREAD), null, null, unitPrice = money(600))),
            ),
            price.fix("no, el pan integral"),
        )
        assertEquals(
            price.copy(lines = listOf(DraftLine(item(BREAD), null, null, unitPrice = money(650)))),
            price.fix("no, 650"),
        )
    }

    @Test
    fun `an amount said without its thousands keeps them`() {
        val light = PhraseDraft(
            PhraseIntent.EXPENSE,
            categoryId = UTILITIES,
            total = money(80_000),
        )
        assertEquals(light.copy(total = money(90_000)), light.fix("no, 90"))
        assertEquals(light.copy(total = money(85_000)), light.fix("no, eran 85 mil"))
        // Lejos del monto anterior, vale como se dijo.
        assertEquals(light.copy(total = money(5)), light.fix("no, 5"))
        val small = light.copy(total = money(80))
        assertEquals(small.copy(total = money(90)), small.fix("no, 90"))
        assertEquals(light.copy(total = Money(9_050)), light.fix("no, 90,50"))
    }
}
