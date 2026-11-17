package io.github.yesidrobayo.shopledger.navigation

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En el teléfono, con `Intent` reales: la actividad principal solo abre destinos de su lista
 * cerrada. Un aviso de producto abre su detalle y el resumen abre lo que se está acabando. Lo
 * desconocido, un id que no es positivo o un pedido sin acción abren la app como siempre.
 */
@RunWith(AndroidJUnit4::class)
class AppOpenRequestTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aProductNoticeOpensThatProduct() {
        val request = readBack(AppOpenRequest.Product(42L))

        assertEquals(AppOpenRequest.Product(42L), request)
        assertEquals(AppNavKey.ProductDetail(42L), request?.key)
    }

    @Test
    fun theSummaryOpensTheLowStockList() {
        val request = readBack(AppOpenRequest.LowStock)

        assertEquals(AppOpenRequest.LowStock, request)
        assertEquals(AppNavKey.Inventory(InventoryFilter.LOW_STOCK), request?.key)
    }

    @Test
    fun anExpiryNoticeOpensTheExpiringList() {
        val request = readBack(AppOpenRequest.Expiring)

        assertEquals(AppOpenRequest.Expiring, request)
        assertEquals(AppNavKey.Inventory(InventoryFilter.EXPIRING), request?.key)
    }

    @Test
    fun aRecurringReminderOpensTheRecurringExpenses() {
        val request = readBack(AppOpenRequest.Recurring)

        assertEquals(AppOpenRequest.Recurring, request)
        assertEquals(AppNavKey.RecurringExpenses, request?.key)
    }

    @Test
    fun theWidgetActionsOpenTheirForms() {
        assertEquals(AppNavKey.NewSale(), readBack(AppOpenRequest.NewSale)?.key)
        assertEquals(
            AppNavKey.Expenses(startNew = true),
            readBack(AppOpenRequest.NewExpense)?.key,
        )
        assertEquals(AppNavKey.Purchase(), readBack(AppOpenRequest.NewPurchase)?.key)
        // Decir nunca lleva una frase: la persona la dice al abrir.
        assertEquals(AppNavKey.Phrase(), readBack(AppOpenRequest.Phrase)?.key)
        assertEquals(AppNavKey.ProductEdit(), readBack(AppOpenRequest.NewProduct)?.key)
        assertEquals(AppNavKey.Backup, readBack(AppOpenRequest.Backup)?.key)
    }

    @Test
    fun theWidgetActionsIgnoreTheirExtras() {
        // Otra app puede lanzar la actividad con la acción y algo más: solo cuenta la acción.
        val intent =
            toMain()
                .setAction(AppOpenRequest.ACTION_NEW_SALE)
                .putExtra(AppOpenRequest.EXTRA_PRODUCT_ID, 7L)

        assertEquals(AppOpenRequest.NewSale, AppOpenRequest.readFrom(intent))
    }

    @Test
    fun anythingElseOpensNothing() {
        assertNull(AppOpenRequest.readFrom(null))
        assertNull(AppOpenRequest.readFrom(toMain()))
        assertNull(AppOpenRequest.readFrom(toMain().setAction(Intent.ACTION_MAIN)))
        assertNull(AppOpenRequest.readFrom(toMain().setAction("otra.app.ABRIR")))
    }

    @Test
    fun aProductWithoutAPositiveIdOpensNothing() {
        val action = AppOpenRequest.ACTION_OPEN_PRODUCT

        assertNull(AppOpenRequest.readFrom(toMain().setAction(action)))
        assertNull(
            AppOpenRequest.readFrom(
                toMain().setAction(action).putExtra(AppOpenRequest.EXTRA_PRODUCT_ID, 0L),
            ),
        )
        assertNull(
            AppOpenRequest.readFrom(
                toMain().setAction(action).putExtra(AppOpenRequest.EXTRA_PRODUCT_ID, -3L),
            ),
        )
        // Un id escrito como texto no es un número: no se adivina.
        assertNull(
            AppOpenRequest.readFrom(
                toMain().setAction(action).putExtra(AppOpenRequest.EXTRA_PRODUCT_ID, "42"),
            ),
        )
    }

    @Test
    fun twoProductsAreTwoDifferentNotices() {
        assertNotEquals(
            AppOpenRequest.Product(1L).requestCode,
            AppOpenRequest.Product(2L).requestCode,
        )
    }

    private fun readBack(request: AppOpenRequest): AppOpenRequest? =
        AppOpenRequest.readFrom(toMain().also(request::writeTo))

    private fun toMain(): Intent = Intent(context, MainActivity::class.java)
}
