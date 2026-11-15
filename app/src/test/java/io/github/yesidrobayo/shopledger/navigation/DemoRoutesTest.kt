package io.github.yesidrobayo.shopledger.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Con datos de ejemplo, las pantallas que dependen de la nube se muestran desactivadas: el
 * respaldo, el cifrado, las copias locales, la cuenta de la nube y conectar un destino. El resto
 * funciona igual, sobre la base de ejemplo.
 */
class DemoRoutesTest {
    @Test
    fun everyScreenThatDependsOnTheCloudIsDisabled() {
        val cloud =
            listOf(
                AppNavKey.Backup,
                AppNavKey.BackupEncryption,
                AppNavKey.LocalCopies,
                AppNavKey.CloudAccountSecurity,
                AppNavKey.BackupDestinationChoice(),
                AppNavKey.BackupDestinationChoice(firstRun = true),
                AppNavKey.WebDavConnect(),
                AppNavKey.WebDavConnect(suggestionKey = "clave inventada"),
                AppNavKey.DocumentSlotsSetup,
            )

        for (key in cloud) assertTrue("$key", key.needsCloud())
    }

    @Test
    fun theRestOfTheAppKeepsWorkingOnTheSampleData() {
        val rest =
            listOf(
                AppNavKey.Home,
                AppNavKey.SaleHistory,
                AppNavKey.ProductList,
                AppNavKey.NewSale(),
                AppNavKey.Settings,
                AppNavKey.BusinessHub,
            )

        for (key in rest) assertFalse("$key", key.needsCloud())
    }
}
