package io.github.yesidrobayo.shopledger.localization

import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El texto visible de las filas iniciales en cada idioma de la app: sale del recurso de su clave
 * mientras nadie la renombre, y del nombre de la persona después. Los textos esperados son los de
 * la guía de redacción, escritos a mano.
 */
@RunWith(AndroidJUnit4::class)
class SeedTextTest {
    private val spanish = resourcesIn("es")
    private val english = resourcesIn("en")
    private val portuguese = resourcesIn("pt")

    @Test
    fun everyInitialKeyHasItsResourceNamedAfterItsTableAndKey() {
        for (table in SeedTable.entries) {
            for (key in table.systemKeys) {
                val res = requireNotNull(SeedText.nameRes(table, key)) { "$table $key" }
                assertEquals(
                    "seed_${table.tableName}_${key.lowercase()}",
                    english.getResourceEntryName(res),
                )
            }
        }
        for (key in SeedTable.UNITS.systemKeys) {
            val symbol = requireNotNull(SeedText.unitSymbolRes(key)) { key }
            val quantity = requireNotNull(SeedText.unitQuantityRes(key)) { key }
            assertEquals("unit_symbol_${key.lowercase()}", english.getResourceEntryName(symbol))
            assertEquals("unit_qty_${key.lowercase()}", english.getResourceEntryName(quantity))
        }
    }

    @Test
    fun everyInitialRowHasATextInEachLanguage() {
        for (resources in listOf(spanish, english, portuguese)) {
            for (table in SeedTable.entries) {
                for (key in table.systemKeys) {
                    val text = SeedText.displayName(resources, table, key, name = null)
                    assertFalse("$table $key", text.isBlank() || text == key)
                }
            }
        }
    }

    @Test
    fun anUneditedRowChangesWithTheLanguage() {
        val cases =
            listOf(
                Case(SeedTable.EXPENSE_CATEGORIES, "RENT", "Alquiler", "Rent", "Aluguel"),
                Case(SeedTable.EXPENSE_CATEGORIES, "OTHER", "Otros", "Other", "Outros"),
                Case(SeedTable.UNITS, "KILOGRAM", "Kilogramo", "Kilogram", "Quilograma"),
                Case(SeedTable.PAYMENT_METHODS, "ON_ACCOUNT", "A crédito", "On credit", "A prazo"),
                Case(SeedTable.PAYMENT_METHODS, "CASH", "Efectivo", "Cash", "Dinheiro"),
                Case(
                    SeedTable.PRICE_LISTS,
                    "DEFAULT",
                    "Lista general",
                    "General price list",
                    "Tabela geral",
                ),
                Case(SeedTable.LOCATIONS, "MAIN", "Principal", "Main", "Principal"),
                Case(SeedTable.MONEY_ACCOUNTS, "CASH", "Efectivo", "Cash", "Dinheiro"),
            )
        for (case in cases) {
            assertEquals(case.spanish, SeedText.displayName(spanish, case.table, case.key, null))
            assertEquals(case.english, SeedText.displayName(english, case.table, case.key, null))
            assertEquals(
                case.portuguese,
                SeedText.displayName(portuguese, case.table, case.key, null),
            )
        }
    }

    @Test
    fun aRenamedRowKeepsThePersonsNameInEveryLanguage() {
        for (resources in listOf(spanish, english, portuguese)) {
            assertEquals(
                "Local",
                SeedText.displayName(resources, SeedTable.EXPENSE_CATEGORIES, "RENT", "Local"),
            )
            // Una fila que creó la persona no tiene clave.
            assertEquals(
                "Papelería",
                SeedText.displayName(resources, SeedTable.EXPENSE_CATEGORIES, null, "Papelería"),
            )
        }
    }

    @Test
    fun regionalVariantsUseTheirLanguageAndAnyOtherLanguageUsesEnglish() {
        val rent = { tag: String ->
            SeedText.displayName(resourcesIn(tag), SeedTable.EXPENSE_CATEGORIES, "RENT", null)
        }
        for (tag in listOf(
            "es-AR",
            "es-MX",
            "es-ES",
            "es-419",
        )) {
            assertEquals(tag, "Alquiler", rent(tag))
        }
        for (tag in listOf("pt-BR", "pt-PT")) assertEquals(tag, "Aluguel", rent(tag))
        for (tag in listOf("fr-FR", "de-DE", "ar-EG")) assertEquals(tag, "Rent", rent(tag))
    }

    @Test
    fun aKeyThisVersionDoesNotKnowIsShownAsItIs() {
        assertNull(SeedText.nameRes(SeedTable.EXPENSE_CATEGORIES, "FUTURE_KEY"))
        assertNull(SeedText.nameRes(SeedTable.PRICE_LISTS, "OTHER"))
        assertNull(SeedText.nameRes(SeedTable.LOCATIONS, "OTHER"))
        assertNull(SeedText.nameRes(SeedTable.MONEY_ACCOUNTS, "OTHER"))
        // Las claves son de cada tabla: la caja de dinero no es el método de pago en efectivo.
        assertNull(SeedText.nameRes(SeedTable.UNITS, "CASH"))
        assertEquals(
            "FUTURE_KEY",
            SeedText.displayName(spanish, SeedTable.PAYMENT_METHODS, "FUTURE_KEY", null),
        )
        assertThrows(IllegalArgumentException::class.java) {
            SeedText.displayName(spanish, SeedTable.UNITS, null, null)
        }
    }

    @Test
    fun aUnitSymbolComesFromThePersonOrFromItsKey() {
        assertEquals("und", SeedText.unitSymbol(spanish, "UNIT", symbol = null))
        assertEquals("pcs", SeedText.unitSymbol(english, "UNIT", symbol = null))
        assertEquals("un", SeedText.unitSymbol(portuguese, "UNIT", symbol = null))
        assertEquals("cx", SeedText.unitSymbol(portuguese, "BOX", symbol = null))
        assertEquals("kg", SeedText.unitSymbol(spanish, "KILOGRAM", symbol = null))
        // El que escribió la persona manda, también en una unidad inicial.
        assertEquals("kilo", SeedText.unitSymbol(english, "KILOGRAM", symbol = "kilo"))
        assertEquals("bdj", SeedText.unitSymbol(spanish, null, symbol = "bdj"))
        // Sin símbolo propio ni clave conocida, no hay símbolo: la cantidad lleva el nombre.
        assertNull(SeedText.unitSymbol(spanish, null, symbol = null))
        assertNull(SeedText.unitSymbol(spanish, "FUTURE_KEY", symbol = null))
        assertNull(SeedText.unitQuantityRes("FUTURE_KEY"))
    }

    /** Una fila inicial y su texto esperado en español, inglés y portugués. */
    private data class Case(
        val table: SeedTable,
        val key: String,
        val spanish: String,
        val english: String,
        val portuguese: String,
    )
}

/**
 * Los recursos de la app en el idioma [tag], como los da el contexto con el idioma elegido para la
 * app, sea cual sea el del teléfono.
 */
internal fun resourcesIn(tag: String): Resources {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocales(LocaleList.forLanguageTags(tag))
    return context.createConfigurationContext(configuration).resources
}
