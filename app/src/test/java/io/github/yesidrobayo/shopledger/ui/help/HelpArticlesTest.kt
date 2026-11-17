package io.github.yesidrobayo.shopledger.ui.help

import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los artículos y los temas de la ayuda sin Android: cada clave es única y estable, cada grupo
 * tiene artículos, y el enlace de ayuda de cada pantalla llega al artículo de la tabla de la
 * pantalla de ayuda, directo si es uno solo.
 */
class HelpArticlesTest {
    @Test
    fun `every article has a unique key that is also its file name`() {
        val keys = HelpArticleId.entries.map { it.key }

        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { Regex("help_[a-z_]+").matches(it) })
        assertEquals(52, keys.size)
    }

    @Test
    fun `every group has articles, in the order of the help screen`() {
        val groups = HelpArticleId.entries.map { it.group }.distinct()

        assertEquals(HelpGroup.entries.toList(), groups)
    }

    @Test
    fun `an article is found by its key, and an unknown key finds nothing`() {
        assertEquals(HelpArticleId.RESTORE, HelpArticleId.ofKey("help_restore"))
        assertNull(HelpArticleId.ofKey("help_unknown"))
        assertNull(HelpArticleId.ofKey(null))
    }

    @Test
    fun `related articles never repeat the article itself`() {
        HelpArticleId.entries.forEach { id ->
            assertFalse(id.name, id in id.related())
            assertTrue(id.name, id.related().isNotEmpty())
        }
    }

    @Test
    fun `targets only open screens of the app`() {
        assertEquals(AppNavKey.Backup, HelpArticleId.BACKUP_LIMITS.target()?.key)
        assertEquals(AppNavKey.BackupEncryption, HelpArticleId.RECOVERY_CODE.target()?.key)
        assertEquals(AppNavKey.ProductList, HelpArticleId.RECIPES_PRODUCTION.target()?.key)
        assertEquals(AppNavKey.Replenishment(), HelpArticleId.RESTOCK.target()?.key)
        assertNull(HelpArticleId.GLOSSARY.target())
        assertNull(HelpArticleId.UPDATES.target())
        assertNull(HelpArticleId.VARIANTS_EXTRAS.target())
    }

    @Test
    fun `each screen link reaches the articles of its row`() {
        val expected =
            mapOf(
                HelpTopic.ONBOARDING to
                    listOf(
                        HelpArticleId.GETTING_STARTED,
                        HelpArticleId.RESTORE,
                    ),
                HelpTopic.HOME to listOf(HelpArticleId.GETTING_STARTED, HelpArticleId.BUSINESS_HUB),
                HelpTopic.BUSINESS_HUB to listOf(HelpArticleId.BUSINESS_HUB),
                HelpTopic.NEW_SALE to listOf(HelpArticleId.FIRST_SALE, HelpArticleId.HELD_SALES),
                HelpTopic.HELD_SALES to listOf(HelpArticleId.HELD_SALES),
                HelpTopic.REGISTER_BY_PHRASE to listOf(HelpArticleId.REGISTER_BY_PHRASE),
                HelpTopic.RECORDS to listOf(HelpArticleId.RECORDS, HelpArticleId.RETURNS_VOIDS),
                HelpTopic.RETURNS to listOf(HelpArticleId.RETURNS_VOIDS),
                HelpTopic.QUOTES_ORDERS to listOf(HelpArticleId.QUOTES_ORDERS),
                HelpTopic.PURCHASES to listOf(HelpArticleId.PURCHASES_SUPPLIERS),
                HelpTopic.PAYABLES to listOf(HelpArticleId.PAYABLES),
                HelpTopic.EXPENSES to listOf(HelpArticleId.EXPENSES),
                HelpTopic.RESTOCK to listOf(HelpArticleId.RESTOCK),
                HelpTopic.PRODUCT_EDIT to
                    listOf(
                        HelpArticleId.PRODUCTS_COSTS,
                        HelpArticleId.VARIANTS_EXTRAS,
                        HelpArticleId.CATEGORIES_FIELDS,
                        HelpArticleId.PHOTOS_CATALOG,
                    ),
                HelpTopic.PRODUCT_DETAIL to
                    listOf(
                        HelpArticleId.MARGIN_PROFIT,
                        HelpArticleId.REPLACEMENT_COST,
                        HelpArticleId.RECIPES_PRODUCTION,
                    ),
                HelpTopic.RECIPES to listOf(HelpArticleId.RECIPES_PRODUCTION),
                HelpTopic.VARIANTS_EXTRAS to listOf(HelpArticleId.VARIANTS_EXTRAS),
                HelpTopic.CATALOG_SETUP to listOf(HelpArticleId.CATEGORIES_FIELDS),
                HelpTopic.PRICE_LISTS to listOf(HelpArticleId.PRICE_LISTS),
                HelpTopic.LOCATIONS to listOf(HelpArticleId.LOCATIONS_TRANSFERS),
                HelpTopic.LABELS to listOf(HelpArticleId.SCANNER_LABELS),
                HelpTopic.CATALOG to listOf(HelpArticleId.PHOTOS_CATALOG),
                HelpTopic.STATISTICS to listOf(HelpArticleId.NUMBERS, HelpArticleId.MARGIN_PROFIT),
                HelpTopic.MANAGEMENT_SUMMARY to
                    listOf(
                        HelpArticleId.NUMBERS,
                        HelpArticleId.NOT_ACCOUNTING,
                    ),
                HelpTopic.PRICE_INDEX to listOf(HelpArticleId.PRICE_INDEX),
                HelpTopic.GOALS to listOf(HelpArticleId.GOALS_LIMITS),
                HelpTopic.PAYMENTS to listOf(HelpArticleId.PAYMENTS_FEES),
                HelpTopic.CUSTOMERS to listOf(HelpArticleId.CUSTOMERS_POINTS, HelpArticleId.CREDIT),
                HelpTopic.MONEY_ACCOUNTS to listOf(HelpArticleId.MONEY_ACCOUNTS),
                HelpTopic.TAXES to listOf(HelpArticleId.TAXES),
                HelpTopic.INVENTORY to listOf(HelpArticleId.STOCK_COUNTS, HelpArticleId.RESTOCK),
                HelpTopic.UNITS to listOf(HelpArticleId.UNITS),
                HelpTopic.LOTS to listOf(HelpArticleId.LOTS),
                HelpTopic.RECEIPTS to listOf(HelpArticleId.RECEIPTS),
                HelpTopic.IMPORT to listOf(HelpArticleId.IMPORT_EXPORT),
                HelpTopic.NOT_ACCOUNTING to listOf(HelpArticleId.NOT_ACCOUNTING),
                HelpTopic.BACKUP to
                    listOf(
                        HelpArticleId.BACKUP_LIMITS,
                        HelpArticleId.RESTORE,
                        HelpArticleId.CLOUD_CHECK,
                    ),
                HelpTopic.ENCRYPTION to listOf(HelpArticleId.RECOVERY_CODE),
                HelpTopic.DESTINATIONS to listOf(HelpArticleId.DESTINATIONS),
                HelpTopic.LOCAL_COPIES to listOf(HelpArticleId.OFFSITE),
                HelpTopic.LOCK_DISCREET to
                    listOf(
                        HelpArticleId.LOCK_DISCREET,
                        HelpArticleId.BUSINESS_PREFERENCES,
                        HelpArticleId.REGION_LANGUAGE,
                        HelpArticleId.WIDGET_TILE,
                    ),
                HelpTopic.TRANSPARENCY to listOf(HelpArticleId.DATA_TRANSPARENCY),
                HelpTopic.AUDIT to listOf(HelpArticleId.AUDIT),
                HelpTopic.STORAGE to listOf(HelpArticleId.DELETE_DATA),
                HelpTopic.CLOUD_ACCOUNT to listOf(HelpArticleId.CLOUD_ACCOUNT),
                HelpTopic.REGION to listOf(HelpArticleId.REGION_LANGUAGE),
                HelpTopic.UPDATES to listOf(HelpArticleId.UPDATES),
            )

        assertEquals(expected, HelpTopic.entries.associateWith { it.articles })
    }

    @Test
    fun `a screen with one article opens it, and one with several opens the list`() {
        assertEquals(
            AppNavKey.Help(articleKey = "help_taxes", topicKey = "taxes"),
            HelpTopic.TAXES.navKey(),
        )
        assertEquals(
            AppNavKey.Help(articleKey = null, topicKey = "backup"),
            HelpTopic.BACKUP.navKey(),
        )
    }

    @Test
    fun `topic keys are unique and found again`() {
        val keys = HelpTopic.entries.map { it.key }

        assertEquals(keys.size, keys.toSet().size)
        HelpTopic.entries.forEach { assertEquals(it, HelpTopic.ofKey(it.key)) }
        assertNull(HelpTopic.ofKey("unknown"))
    }

    @Test
    fun `every article but the glossary is opened by the help button of some screen`() {
        // El glosario se abre desde las ayudas de término, no desde una pantalla.
        val opened = HelpTopic.entries.flatMap { it.articles }.toSet()

        assertEquals(
            listOf(HelpArticleId.GLOSSARY),
            HelpArticleId.entries.filterNot { it in opened },
        )
    }

    @Test
    fun `every article has its text in the three languages`() {
        HelpArticleId.entries.forEach { id ->
            LANGUAGE_DIRS.forEach { dir ->
                val file = File(RES, "$dir/${id.key}.md")
                assertTrue("${file.path} falta", file.isFile)
                assertTrue("${file.path} está vacío", file.readText().isNotBlank())
            }
        }
    }

    @Test
    fun `a link inside an article only points to an article that exists`() {
        val keys = HelpArticleId.entries.map { it.key }.toSet()
        val link = Regex("""\]\((help_[a-z_]+)\)""")

        LANGUAGE_DIRS.forEach { dir ->
            File(RES, dir).listFiles { file -> file.name.startsWith("help_") }.orEmpty().forEach {
                link.findAll(it.readText()).forEach { found ->
                    assertTrue("${it.path}: ${found.groupValues[1]}", found.groupValues[1] in keys)
                }
            }
        }
    }

    private companion object {
        /** Los recursos de la app, desde la carpeta del módulo, donde corren las pruebas JVM. */
        val RES = File("src/main/res")
        val LANGUAGE_DIRS = listOf("raw", "raw-es", "raw-pt")
    }
}
