package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.phrase.NumberStyle
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseCatalog
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseConversion
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLanguage
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseName
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseProduct
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseReading
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseUnit

/**
 * Catálogos inventados para las pruebas del intérprete, uno por idioma. Los identificadores se
 * agrupan por centenas para leer las pruebas: unidades del 1, productos del 100, clientes del 200,
 * proveedores del 300, métodos de pago del 400 y categorías del 500.
 */
internal object PhraseCatalogs {
    val COMMA_DECIMAL = NumberStyle(decimal = ',', grouping = '.')
    val POINT_DECIMAL = NumberStyle(decimal = '.', grouping = ',')

    const val UNIT = 1L
    const val KILOGRAM = 2L
    const val GRAM = 3L
    const val POUND = 4L
    const val LITER = 5L
    const val MILLILITER = 6L
    const val METER = 7L
    const val BOX = 8L
    const val DOZEN = 9L
    const val SACK = 10L
    const val PACK = 11L
    const val TRAY = 12L
    const val TON = 13L
    const val ARROBA = 14L

    const val BREAD = 100L
    const val WHOLE_BREAD = 101L
    const val WHEAT_FLOUR = 102L
    const val CORN_FLOUR = 103L
    const val EGG = 104L
    const val SODA_SMALL = 105L
    const val SODA_BIG = 106L
    const val MILK = 107L
    const val FARMER_CHEESE = 108L
    const val MOZZARELLA = 109L
    const val EMPANADA = 110L
    const val HAIRCUT = 111L
    const val CHOCO_CAKE = 112L
    const val VANILLA_CAKE = 113L
    const val COOKIES = 114L
    const val SCREW = 115L
    const val SALT_PEPPER = 116L
    const val ORANGE_JUICE = 117L
    const val SMALL_JUICE = 118L
    const val CABLE = 119L
    const val BREAKFAST = 120L
    const val JAR = 121L
    const val JAM = 122L
    const val SILO = 123L
    const val CACAO = 124L
    const val PLATES = 125L
    const val MANGO = 126L
    const val SLEEVE = 127L

    const val MARIA = 200L
    const val MARIA_T = 201L
    const val ROSA = 202L
    const val CARLOS = 203L

    const val RAMIRO = 300L
    const val CENTRAL = 301L
    const val HARDWARE = 302L

    const val CASH = 400L
    const val CARD = 401L
    const val TRANSFER = 402L
    const val ON_ACCOUNT = 403L
    const val OWN_WALLET = 404L

    const val RENT = 500L
    const val UTILITIES = 501L
    const val TRANSPORT = 502L
    const val PETS = 503L

    private val units = listOf(
        PhraseUnit(UNIT, "unit", "Unidad", "und", UnitKind.COUNT, null),
        PhraseUnit(KILOGRAM, "kilogram", "Kilogramo", "kg", UnitKind.MASS, 1_000_000L),
        PhraseUnit(GRAM, "gram", "Gramo", "g", UnitKind.MASS, 1_000L),
        PhraseUnit(POUND, "pound", "Libra", "lb", UnitKind.MASS, 454_000L),
        PhraseUnit(LITER, "liter", "Litro", "L", UnitKind.VOLUME, 1_000_000L),
        PhraseUnit(MILLILITER, "milliliter", "Mililitro", "ml", UnitKind.VOLUME, 1_000L),
        PhraseUnit(METER, "meter", "Metro", "m", UnitKind.LENGTH, 1_000L),
        PhraseUnit(BOX, "box", "Caja", null, UnitKind.COUNT, null),
        PhraseUnit(DOZEN, "dozen", "Docena", null, UnitKind.COUNT, null),
        PhraseUnit(SACK, null, "Bulto", null, UnitKind.COUNT, null),
        PhraseUnit(PACK, "pack", "Paquete", null, UnitKind.COUNT, null),
        PhraseUnit(TRAY, "tray", "Cubeta", null, UnitKind.COUNT, null),
        PhraseUnit(TON, null, "Tonelada", "t", UnitKind.MASS, 1_000_000_000L),
        PhraseUnit(ARROBA, null, "Arroba", null, UnitKind.MASS, null),
    )

    private val ENGLISH_UNITS = mapOf(
        UNIT to "Unit", KILOGRAM to "Kilogram", GRAM to "Gram", POUND to "Pound", LITER to "Liter",
        MILLILITER to "Milliliter", METER to "Meter", BOX to "Box", DOZEN to "Dozen",
        SACK to "Sack", PACK to "Pack", TRAY to "Tray", TON to "Ton", ARROBA to "Arroba",
    )

    private val PORTUGUESE_UNITS = mapOf(
        UNIT to "Unidade", KILOGRAM to "Quilograma", GRAM to "Grama", POUND to "Libra",
        LITER to "Litro",
        MILLILITER to "Mililitro", METER to "Metro", BOX to "Caixa", DOZEN to "Dúzia",
        SACK to "Saco", PACK to "Pacote",
        TRAY to "Bandeja", TON to "Tonelada", ARROBA to "Arroba",
    )

    private fun product(
        id: Long,
        name: String,
        base: Long,
        vararg conversions: Pair<Long, Long>,
        sale: Boolean = true,
        purchase: Boolean = true,
        recipe: Boolean = false,
        mode: ProductStockMode = ProductStockMode.TRACKED,
    ) = PhraseProduct(
        id = id,
        name = name,
        baseUnitId = base,
        conversions = conversions.map { (unit, factor) -> PhraseConversion(unit, factor) },
        forSale = sale,
        forPurchase = purchase,
        hasRecipe = recipe,
        stockMode = mode,
    )

    val spanish = PhraseCatalog(
        products = listOf(
            product(BREAD, "Pan", UNIT, purchase = false, recipe = true),
            product(WHOLE_BREAD, "Pan integral", UNIT, purchase = false, recipe = true),
            product(
                WHEAT_FLOUR,
                "Harina de trigo",
                KILOGRAM,
                POUND to 454L,
                SACK to 50_000L,
                sale = false,
            ),
            product(CORN_FLOUR, "Harina de maíz", KILOGRAM, POUND to 454L),
            product(EGG, "Huevo", UNIT, DOZEN to 12_000L, TRAY to 30_000L),
            product(SODA_SMALL, "Gaseosa 400 ml", UNIT, BOX to 12_000L),
            product(SODA_BIG, "Gaseosa 1,5 L", UNIT, BOX to 6_000L),
            product(MILK, "Leche entera", LITER),
            product(FARMER_CHEESE, "Queso campesino", KILOGRAM),
            product(MOZZARELLA, "Queso mozarela", KILOGRAM),
            product(EMPANADA, "Empanada", UNIT, purchase = false, recipe = true),
            product(
                HAIRCUT,
                "Corte de cabello",
                UNIT,
                purchase = false,
                mode = ProductStockMode.UNTRACKED,
            ),
            product(CHOCO_CAKE, "Torta de chocolate", UNIT, purchase = false, recipe = true),
            product(VANILLA_CAKE, "Torta de vainilla", UNIT, purchase = false, recipe = true),
            product(COOKIES, "Galletas", PACK),
            product(SCREW, "Tornillo 1/4", UNIT, BOX to 100_000L),
            product(SALT_PEPPER, "Sal y pimienta", UNIT),
            product(ORANGE_JUICE, "Jugo de naranja", UNIT, purchase = false, recipe = true),
            product(SMALL_JUICE, "Jugo 250 ml", UNIT),
            product(CABLE, "Cable eléctrico", METER),
            product(
                BREAKFAST,
                "Combo desayuno",
                UNIT,
                purchase = false,
                mode = ProductStockMode.COMPONENTS,
            ),
            product(JAR, "Frasco de vidrio", UNIT, sale = false),
            product(JAM, "Mermelada de fresa", UNIT, purchase = false, recipe = true),
            product(SILO, "Silo 999999999999 t", UNIT),
            product(CACAO, "Cacao", ARROBA),
            product(PLATES, "Platos 20 und", UNIT),
            product(MANGO, "Mango", UNIT),
            product(SLEEVE, "Manga", UNIT),
        ),
        units = units,
        paymentMethods = listOf(
            PhraseName(CASH, "cash", "Efectivo"),
            PhraseName(CARD, "card", "Tarjeta"),
            PhraseName(TRANSFER, "transfer", "Transferencia"),
            PhraseName(ON_ACCOUNT, "on_account", "A crédito"),
            PhraseName(OWN_WALLET, null, "Nequi"),
        ),
        suppliers = listOf(
            PhraseName(RAMIRO, null, "Don Ramiro"),
            PhraseName(CENTRAL, null, "Distribuidora Central"),
            PhraseName(HARDWARE, null, "Ferretería Moderna"),
        ),
        customers = listOf(
            PhraseName(MARIA, null, "María Gómez"),
            PhraseName(MARIA_T, null, "María Torres"),
            PhraseName(ROSA, null, "Doña Rosa"),
            PhraseName(CARLOS, null, "Carlos Ruiz"),
        ),
        expenseCategories = listOf(
            PhraseName(RENT, "rent", "Alquiler"),
            PhraseName(UTILITIES, "utilities", "Servicios básicos"),
            PhraseName(TRANSPORT, "transport", "Transporte"),
            PhraseName(PETS, null, "Mascotas"),
        ),
    )

    val english = PhraseCatalog(
        products = listOf(
            product(BREAD, "Bread roll", UNIT, purchase = false, recipe = true),
            product(WHEAT_FLOUR, "Wheat flour", KILOGRAM, POUND to 454L, sale = false),
            product(EGG, "Egg", UNIT, DOZEN to 12_000L),
            product(CHOCO_CAKE, "Chocolate cake", UNIT, purchase = false, recipe = true),
            product(COOKIES, "Cookies", PACK),
            product(SODA_SMALL, "Soda 400 ml", UNIT),
            product(SODA_BIG, "Soda 1.5 L", UNIT),
            product(MILK, "Whole milk", LITER),
        ),
        units = units.map { it.copy(name = ENGLISH_UNITS.getValue(it.id)) },
        paymentMethods = listOf(
            PhraseName(CASH, "cash", "Cash"),
            PhraseName(ON_ACCOUNT, "on_account", "On credit"),
        ),
        suppliers = listOf(PhraseName(RAMIRO, null, "Mr. Ramiro")),
        customers = listOf(
            PhraseName(MARIA, null, "Mary Smith"),
            PhraseName(ROSA, null, "Miss Rose"),
        ),
        expenseCategories = listOf(
            PhraseName(RENT, "rent", "Rent"),
            PhraseName(TRANSPORT, "transport", "Transport"),
        ),
    )

    val portuguese = PhraseCatalog(
        products = listOf(
            product(BREAD, "Pão", UNIT, purchase = false, recipe = true),
            product(WHEAT_FLOUR, "Farinha de trigo", KILOGRAM, sale = false),
            product(EGG, "Ovo", UNIT, DOZEN to 12_000L),
            product(ORANGE_JUICE, "Suco de laranja", UNIT, purchase = false, recipe = true),
            product(SODA_SMALL, "Refrigerante 400 ml", UNIT),
            product(SODA_BIG, "Refrigerante 1,5 L", UNIT),
        ),
        units = units.map { it.copy(name = PORTUGUESE_UNITS.getValue(it.id)) },
        paymentMethods = listOf(
            PhraseName(CASH, "cash", "Dinheiro"),
            PhraseName(ON_ACCOUNT, "on_account", "A prazo"),
        ),
        suppliers = listOf(PhraseName(RAMIRO, null, "Seu Ramiro")),
        customers = listOf(
            PhraseName(MARIA, null, "Maria Souza"),
            PhraseName(ROSA, null, "Dona Rosa"),
        ),
        expenseCategories = listOf(
            PhraseName(RENT, "rent", "Aluguel"),
            PhraseName(UTILITIES, "utilities", "Contas de consumo"),
        ),
    )

    fun spanish(text: String, catalog: PhraseCatalog = spanish): PhraseReading =
        PhraseInterpreter.read(text, PhraseLanguage.ES, COMMA_DECIMAL, 2, catalog)

    fun english(text: String): PhraseReading =
        PhraseInterpreter.read(text, PhraseLanguage.EN, POINT_DECIMAL, 2, english)

    fun portuguese(text: String): PhraseReading =
        PhraseInterpreter.read(text, PhraseLanguage.PT, COMMA_DECIMAL, 2, portuguese)

    /** Unidades enteras de la moneda de prueba, que tiene dos decimales. */
    fun money(major: Long): Money = Money(major * 100)

    fun units(whole: Long): QuantityMilli = QuantityMilli(whole * 1_000)
}
