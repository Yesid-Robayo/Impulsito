package io.github.yesidrobayo.shopledger.ui.help

import io.github.yesidrobayo.shopledger.navigation.AppNavKey
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
        assertEquals(26, keys.size)
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
        assertNull(HelpArticleId.GLOSSARY.target())
        assertNull(HelpArticleId.UPDATES.target())
    }

    @Test
    fun `each screen link reaches the articles of its row`() {
        val expected =
            mapOf(
                HelpTopic.ONBOARDING to
                    listOf(HelpArticleId.GETTING_STARTED, HelpArticleId.RESTORE),
                HelpTopic.HOME to listOf(HelpArticleId.GETTING_STARTED),
                HelpTopic.NEW_SALE to listOf(HelpArticleId.FIRST_SALE),
                HelpTopic.PRODUCT_EDIT to listOf(HelpArticleId.PRODUCTS_COSTS),
                HelpTopic.PRODUCT_DETAIL to
                    listOf(HelpArticleId.MARGIN_PROFIT, HelpArticleId.REPLACEMENT_COST),
                HelpTopic.STATISTICS to listOf(HelpArticleId.MARGIN_PROFIT),
                HelpTopic.PAYMENTS to listOf(HelpArticleId.PAYMENTS_FEES),
                HelpTopic.CUSTOMERS to listOf(HelpArticleId.CREDIT),
                HelpTopic.MONEY_ACCOUNTS to listOf(HelpArticleId.MONEY_ACCOUNTS),
                HelpTopic.TAXES to listOf(HelpArticleId.TAXES),
                HelpTopic.INVENTORY to listOf(HelpArticleId.STOCK_COUNTS),
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
                HelpTopic.LOCK_DISCREET to listOf(HelpArticleId.LOCK_DISCREET),
                HelpTopic.STORAGE to listOf(HelpArticleId.DELETE_DATA),
                HelpTopic.CLOUD_ACCOUNT to listOf(HelpArticleId.CLOUD_ACCOUNT),
                HelpTopic.UPDATES to listOf(HelpArticleId.UPDATES),
            )

        assertEquals(expected, HelpTopic.entries.associateWith { it.articles })
    }

    @Test
    fun `a screen with one article opens it, and one with several opens the list`() {
        assertEquals(
            AppNavKey.Help(articleKey = "help_first_sale", topicKey = "new_sale"),
            HelpTopic.NEW_SALE.navKey(),
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
}
