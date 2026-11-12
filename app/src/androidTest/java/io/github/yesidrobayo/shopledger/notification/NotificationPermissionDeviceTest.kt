package io.github.yesidrobayo.shopledger.notification

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.data.BackupNotification
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.port.LowStockNotice
import io.github.yesidrobayo.shopledger.ui.permission.NotificationPermissionPrompt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En Android 13 o superior, con el permiso de avisos dado: los avisos del respaldo y los de stock
 * salen. Antes de que el manifiesto pidiera el permiso, en esas versiones no salía ninguno.
 *
 * Sin el permiso, que es como queda una instalación nueva, las mismas llamadas responden que no
 * salió, sin fallar: lo comprueban `BackupNotifierDeviceTest` y `LowStockNotifierDeviceTest`, que
 * se corren antes de dar el permiso. Dar un permiso no reinicia el proceso; quitarlo sí, por eso
 * esta prueba solo lo da.
 */
@RunWith(AndroidJUnit4::class)
class NotificationPermissionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as ShopLedgerApp

    @After
    fun dismiss() {
        app.getSystemService(NotificationManager::class.java).cancelAll()
    }

    @Test
    fun withThePermissionGrantedBackupAndStockNoticesGoOut() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        instrumentation.uiAutomation.grantRuntimePermission(
            app.packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        val texts = app.container.localizedContext
        NotificationChannels(app, NotificationChannels.APP_CHANNELS).refresh(texts.context())
        val settings = (app.container.data.open() as DataAccess.Ready).ports.settings
        val stock = AndroidLowStockNotifier(app, texts) { settings.get() }

        assertFalse(NotificationPermissionPrompt.needsAsking(app))
        assertFalse(NotificationPermissionPrompt.stockAlertsBlocked(app))
        assertTrue(app.container.backupNotifier.show(BackupNotification.Stale))
        assertTrue(
            stock.show(
                listOf(
                    LowStockNotice(
                        alertId = 1L,
                        productId = 1L,
                        productName = "Producto de prueba",
                        onHand = QuantityMilli(1_000L),
                        minStock = QuantityMilli(5_000L),
                        unit = null,
                    ),
                ),
            ),
        )
    }
}
