package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIssue
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseReading
import io.github.yesidrobayo.shopledger.domain.model.phrase.ProductMatch
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.BREAD
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.BREAKFAST
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CHOCO_CAKE
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.COOKIES
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.EGG
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.EMPANADA
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.HAIRCUT
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.JAM
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.LITER
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.MILK
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.SODA_BIG
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.SODA_SMALL
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.VANILLA_CAKE
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.spanish
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.units
import org.junit.Assert.assertEquals
import org.junit.Test

class PhraseStockTest {
    private fun line(product: ProductMatch, quantity: QuantityMilli? = null, unit: Long? = null) =
        PhraseLine(product, quantity, unit, null, null)

    private fun adjustment(reason: StockAdjustmentReason, vararg lines: PhraseLine) = PhraseReading(
        intent = PhraseIntent.STOCK_ADJUSTMENT,
        reason = reason,
        lines = lines.toList(),
    )

    @Test
    fun `producing something with a recipe`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.PRODUCTION,
                lines = listOf(line(ProductMatch.Found(EMPANADA), units(20))),
            ),
            spanish("hice 20 empanadas"),
        )
        assertEquals(
            listOf(line(ProductMatch.Choose(listOf(CHOCO_CAKE, VANILLA_CAKE)), units(3))),
            spanish("preparé 3 tortas").lines,
        )
        assertEquals(
            listOf(line(ProductMatch.Found(BREAD), units(50))),
            spanish("horneé 50 panes").lines,
        )
    }

    @Test
    fun `producing something without a recipe is flagged`() {
        val reading = spanish("hice 5 cortes de cabello")
        assertEquals(listOf(line(ProductMatch.Found(HAIRCUT), units(5))), reading.lines)
        assertEquals(setOf(PhraseIssue.NO_RECIPE), reading.issues)
    }

    @Test
    fun `producing what is inside a container`() {
        assertEquals(
            listOf(line(ProductMatch.Found(JAM), units(3))),
            spanish("hice 3 frascos de mermelada").lines,
        )
    }

    @Test
    fun `each reason of an adjustment`() {
        assertEquals(
            adjustment(StockAdjustmentReason.DAMAGED, line(ProductMatch.Found(EGG), units(3))),
            spanish("se dañaron 3 huevos"),
        )
        assertEquals(
            adjustment(StockAdjustmentReason.DAMAGED, line(ProductMatch.Found(BREAD), units(2))),
            spanish("se me dañaron 2 panes"),
        )
        assertEquals(
            adjustment(
                StockAdjustmentReason.EXPIRED,
                line(ProductMatch.Found(MILK), units(2), LITER),
            ),
            spanish("se vencieron 2 litros de leche"),
        )
        assertEquals(
            adjustment(
                StockAdjustmentReason.LOST_OR_STOLEN,
                line(ProductMatch.Found(EGG), units(4)),
            ),
            spanish("se robaron 4 huevos"),
        )
        assertEquals(
            adjustment(StockAdjustmentReason.OWN_USE, line(ProductMatch.Found(EMPANADA), units(2))),
            spanish("me comí 2 empanadas"),
        )
        assertEquals(
            adjustment(
                StockAdjustmentReason.GIFT_OR_SAMPLE,
                line(ProductMatch.Found(COOKIES), units(2)),
            ),
            spanish("di de muestra 2 galletas"),
        )
        assertEquals(
            adjustment(
                StockAdjustmentReason.COUNT_CORRECTION,
                line(ProductMatch.Choose(listOf(SODA_SMALL, SODA_BIG)), units(12)),
            ),
            spanish("hay 12 gaseosas"),
        )
    }

    @Test
    fun `the more specific reason wins`() {
        assertEquals(
            adjustment(StockAdjustmentReason.EXPIRED, line(ProductMatch.Found(MILK))),
            spanish("se dañó la leche por vencida"),
        )
        assertEquals(
            StockAdjustmentReason.LOST_OR_STOLEN,
            spanish("se dañaron y se perdieron 2 huevos").reason,
        )
        assertEquals(StockAdjustmentReason.OWN_USE, spanish("de regalo me comí 2 panes").reason)
        assertEquals(
            StockAdjustmentReason.GIFT_OR_SAMPLE,
            spanish("conté que regalé 2 panes").reason,
        )
        assertEquals(StockAdjustmentReason.DAMAGED, spanish("conté 2 panes rotos").reason)
    }

    @Test
    fun `an adjustment prefers products with their own stock`() {
        assertEquals(
            listOf(line(ProductMatch.Found(BREAKFAST), units(2))),
            spanish("se dañaron 2 combos desayuno").lines,
        )
        assertEquals(
            listOf(line(ProductMatch.Found(BREAD), units(2))),
            spanish("se dañaron 2 panes").lines,
        )
    }
}
