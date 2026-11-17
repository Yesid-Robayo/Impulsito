package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.phrase.NameMatch
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseReading
import io.github.yesidrobayo.shopledger.domain.model.phrase.ProductMatch
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.BREAD
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CASH
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CHOCO_CAKE
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.COOKIES
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.DOZEN
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.EGG
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.GRAM
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.KILOGRAM
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.MARIA
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.MILK
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.ON_ACCOUNT
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.ORANGE_JUICE
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.POUND
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.RAMIRO
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.RENT
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.ROSA
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.SODA_BIG
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.SODA_SMALL
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.UTILITIES
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.WHEAT_FLOUR
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.english
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.money
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.portuguese
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.units
import org.junit.Assert.assertEquals
import org.junit.Test

class PhraseLanguagesTest {
    private fun line(
        product: ProductMatch,
        quantity: QuantityMilli? = null,
        unit: Long? = null,
        unitPrice: Long? = null,
        lineTotal: Long? = null,
    ) = PhraseLine(product, quantity, unit, unitPrice?.let(::money), lineTotal?.let(::money))

    @Test
    fun `an english sale with a point decimal and a payment`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.SALE,
                lines = listOf(
                    line(ProductMatch.Found(BREAD), units(3)),
                    line(
                        ProductMatch.Choose(listOf(SODA_SMALL, SODA_BIG)),
                        units(2),
                        unitPrice = 1,
                    ),
                ),
                payment = NameMatch.Found(CASH),
            ),
            english("I sold 3 bread rolls and 2 sodas at 1.00 each in cash"),
        )
    }

    @Test
    fun `english numbers in words, halves and an article as one`() {
        assertEquals(
            listOf(
                line(ProductMatch.Found(WHEAT_FLOUR), QuantityMilli(2_500), POUND, lineTotal = 12),
            ),
            english("bought two and a half pounds of wheat flour for 12").lines,
        )
        assertEquals(
            listOf(line(ProductMatch.Found(EGG), units(1), DOZEN)),
            english("sold a dozen eggs").lines,
        )
        assertEquals(
            listOf(line(ProductMatch.Found(MILK), QuantityMilli(500))),
            english("sold half a whole milk").lines,
        )
        assertEquals(
            listOf(line(ProductMatch.Found(COOKIES), units(250))),
            english("sold two hundred fifty cookies").lines,
        )
        assertEquals(money(1500), english("sold 2 cookies for 1.5 grand").lines.single().lineTotal)
        assertEquals(money(1500), english("sold 2 cookies for 1,500").lines.single().lineTotal)
    }

    @Test
    fun `english payments, recipes and adjustments`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.CUSTOMER_PAYMENT,
                party = NameMatch.Found(MARIA),
                total = money(20),
            ),
            english("Mary paid me 20"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.EXPENSE,
                category = NameMatch.Found(RENT),
                total = money(800),
            ),
            english("paid the rent 800"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.SUPPLIER_PAYMENT,
                party = NameMatch.Found(RAMIRO),
                total = money(200),
            ),
            english("I paid Mr. Ramiro 200"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.RECIPE,
                target = ProductMatch.Found(CHOCO_CAKE),
                lines = listOf(
                    line(ProductMatch.Found(WHEAT_FLOUR), units(200), GRAM),
                    line(ProductMatch.Found(EGG), units(3)),
                ),
            ),
            english("the chocolate cake takes 200 grams of wheat flour and 3 eggs"),
        )
        assertEquals(StockAdjustmentReason.DAMAGED, english("5 eggs got broken").reason)
        assertEquals(
            listOf(line(ProductMatch.Found(MILK), units(3))),
            english("3 bottles of whole milk expired").lines,
        )
    }

    @Test
    fun `english on credit with someone who is not a customer`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.SALE,
                lines = listOf(line(ProductMatch.Found(EGG), units(1), DOZEN)),
                payment = NameMatch.Found(ON_ACCOUNT),
                party = NameMatch.Unknown("Mr Brown"),
                credit = true,
            ),
            english("sold a dozen eggs to Mr. Brown on credit"),
        )
    }

    @Test
    fun `english new product, price change and cost`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.NEW_PRODUCT,
                target = ProductMatch.Unknown("Oatmeal cookie"),
                price = money(3),
            ),
            english("I'm going to sell oatmeal cookies at 3"),
        )
        assertEquals(
            listOf(line(ProductMatch.Found(MILK), unitPrice = 2)),
            english("the price of whole milk is now 2").lines,
        )
        assertEquals(PhraseIntent.COST_CORRECTION, english("the cost of wheat flour is 1").intent)
    }

    @Test
    fun `a portuguese sale with plural in ões and a payment`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.SALE,
                lines = listOf(
                    line(ProductMatch.Found(BREAD), units(3)),
                    line(ProductMatch.Choose(listOf(SODA_SMALL, SODA_BIG)), units(2)),
                ),
                payment = NameMatch.Found(CASH),
            ),
            portuguese("vendi 3 pães e 2 refrigerantes no dinheiro"),
        )
        assertEquals(
            listOf(line(ProductMatch.Found(ORANGE_JUICE), units(1))),
            portuguese("vendi um suco de laranja").lines,
        )
    }

    @Test
    fun `portuguese halves, money and credit`() {
        assertEquals(
            listOf(line(ProductMatch.Found(WHEAT_FLOUR), QuantityMilli(1_500), KILOGRAM)),
            portuguese("comprei um quilo e meio de farinha de trigo").lines,
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.EXPENSE,
                category = NameMatch.Found(UTILITIES),
                total = money(120),
            ),
            portuguese("paguei a conta de luz 120 reais"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.CUSTOMER_PAYMENT,
                party = NameMatch.Found(ROSA),
                total = money(50),
            ),
            portuguese("Dona Rosa me pagou 50 reais"),
        )
        assertEquals(true, portuguese("vendi 2 pães fiado").credit)
        assertEquals(
            listOf(line(ProductMatch.Found(EGG), units(1_250))),
            portuguese("vendi mil duzentos e cinquenta ovos").lines,
        )
    }
}
