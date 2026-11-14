package io.github.yesidrobayo.shopledger.ui.order

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.document.OrderStatus
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los pedidos en el dispositivo: la lista con la entrega vencida, el vacío que invita a crear, el
 * detalle con su avance, la falta de stock, el anticipo y la acción de cada estado, y cancelar con
 * anticipo. Con `captureDir`, las capturas de la puerta de calidad en claro y en oscuro. Datos
 * inventados.
 */
@RunWith(AndroidJUnit4::class)
class OrderScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resources = instrumentation.targetContext.resources
    private val listActions = mutableListOf<OrdersAction>()
    private val detailActions = mutableListOf<OrderDetailAction>()

    private fun text(id: Int) = resources.getString(id)

    @Test
    fun theListMarksTheOverdueDeliveryAndOpensOne() {
        show { OrdersScreen(LIST, { listActions += it }) }

        compose.onNodeWithText(text(R.string.order_status_overdue)).assertIsDisplayed()
        compose.onNodeWithText(PreviewSamples.CUSTOMER_NAME).performClick()
        assertTrue(OrdersAction.Open(58) in listActions)
        compose.onNodeWithText(text(R.string.order_filter_ready)).performClick()
        assertTrue(OrdersAction.SelectFilter(OrderStatus.READY) in listActions)
    }

    @Test
    fun withoutOrdersTheEmptyStateInvitesToCreateOne() {
        show { OrdersScreen(OrdersUiState(content = OrdersContent.Empty), { listActions += it }) }

        compose.onNodeWithText(text(R.string.order_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.order_empty_body)).assertIsDisplayed()
    }

    @Test
    fun aPendingOrderShowsItsTrackShortageAndDeposit() {
        show { OrderDetailScreen(detailState(OrderStatus.PENDING), { detailActions += it }) }

        val track = resources.getString(
            R.string.a11y_order_track,
            text(R.string.order_status_pending),
            1,
            3,
        )
        compose.onNodeWithContentDescription(track).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.order_summary_deposit)).performScrollTo()
        compose.onNodeWithText(text(R.string.order_summary_due)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.order_deposit_action)).performClick()
        assertTrue(OrderDetailAction.OpenDeposit in detailActions)
        compose.onNodeWithText(text(R.string.order_mark_ready_action)).performClick()
        assertTrue(OrderDetailAction.MarkReady in detailActions)
    }

    @Test
    fun aReadyOrderDeliversAndGoesBackToPendingFromTheMenu() {
        show { OrderDetailScreen(detailState(OrderStatus.READY), { detailActions += it }) }

        compose.onNodeWithText(text(R.string.order_deliver_action)).performClick()
        assertTrue(OrderDetailAction.Deliver in detailActions)
        compose.onNodeWithContentDescription(text(R.string.a11y_more_options)).performClick()
        compose.onNodeWithText(text(R.string.order_back_to_pending_action)).performClick()
        assertTrue(OrderDetailAction.BackToPending in detailActions)
    }

    @Test
    fun cancellingWithADepositSaysItStaysAsCredit() {
        val state = detailState(OrderStatus.PENDING)
            .copy(dialog = OrderDetailDialog.ConfirmCancel(PreviewSamples.CUSTOMER_NAME))
        show { OrderDetailScreen(state, { detailActions += it }) }

        val body = resources.getString(
            R.string.order_cancel_confirm_body_deposit,
            PreviewSamples.CUSTOMER_NAME,
        )
        compose.onNodeWithText(body).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.order_cancel_action)).performClick()
        assertTrue(OrderDetailAction.ConfirmCancel in detailActions)
    }

    @Test
    fun aDeliveredOrderOffersItsSaleWithoutActionBar() {
        show { OrderDetailScreen(detailState(OrderStatus.DELIVERED), { detailActions += it }) }

        compose.onNodeWithText(text(R.string.order_view_sale_action)).performClick()
        assertTrue(OrderDetailAction.ViewSale in detailActions)
        compose.onNodeWithText(text(R.string.order_deliver_action)).assertDoesNotExist()
    }

    @Test
    fun drawsOrdersInBothModes() {
        val name = InstrumentationRegistry.getArguments().getString("captureDir")
        assumeTrue("Sin captureDir no hay capturas", name != null)
        val dir = File(instrumentation.targetContext.cacheDir, requireNotNull(name)).apply {
            mkdirs()
        }
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            show(dark) { OrdersScreen(LIST, {}) }
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$mode-orders-list.png"))
            show(dark) { OrdersScreen(OrdersUiState(content = OrdersContent.Empty), {}) }
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$mode-orders-empty.png"))
            show(dark) { OrderDetailScreen(detailState(OrderStatus.PENDING), {}) }
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$mode-order-detail.png"))
            show(dark) {
                OrderDetailScreen(
                    detailState(OrderStatus.READY).copy(deposit = DepositSheet()),
                    {},
                )
            }
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$mode-order-deposit.png"))
            show(dark) {
                OrderDetailScreen(
                    detailState(OrderStatus.PENDING).copy(
                        dialog = OrderDetailDialog.ConfirmCancel(PreviewSamples.CUSTOMER_NAME),
                    ),
                    {},
                )
            }
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$mode-order-cancel.png"))
        }
    }

    private fun detailState(status: OrderStatus) = OrderDetailUiState(
        content = OrderDetailContent.Ready(
            OrderDetail(
                id = 58,
                status = status,
                customerId = 30,
                customerName = PreviewSamples.CUSTOMER_NAME,
                dueDate = DUE,
                overdue = false,
                address = PreviewSamples.SECOND_NAME,
                note = null,
                total = Money(50_900),
                products = Money(45_900),
                shipping = Money(5_000),
                deposit = if (status.isOpen) Money(20_000) else Money.ZERO,
                due = if (status.isOpen) Money(30_900) else Money(50_900),
                lines = listOf(
                    OrderLineUi(
                        PreviewSamples.SHORT_NAME,
                        QuantityMilli(2_000),
                        null,
                        Money(4_200),
                        Money(8_400),
                        QuantityMilli(2_000),
                        QuantityMilli.ZERO,
                    ),
                    OrderLineUi(
                        PreviewSamples.LONG_NAME,
                        QuantityMilli(3_000),
                        null,
                        Money(12_500),
                        Money(37_500),
                        QuantityMilli(3_000),
                        QuantityMilli(1_000),
                    ),
                ),
                deliveredSaleId = if (status == OrderStatus.DELIVERED) 7 else null,
            ),
        ),
    )

    private fun show(dark: Boolean = false, content: @Composable () -> Unit) {
        compose.runOnUiThread {
            compose.activity.setContent {
                AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = dark) {
                    CompositionLocalProvider(
                        LocalAppFormats provides previewFormats(),
                        LocalDiscreetMode provides false,
                    ) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun save(file: File) {
        val bitmap =
            requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Sin captura" }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        const val SETTLE_MILLIS = 900L
        val TODAY = BusinessDate(LocalDate(2026, 11, 5))
        val DUE = BusinessDate(LocalDate(2026, 11, 28))

        fun item(id: Long, name: String?, due: BusinessDate?, total: Money, overdue: Boolean) =
            OrderItem(id, name, due, total, OrderStatus.PENDING, overdue)

        val LIST = OrdersUiState(
            content = OrdersContent.Rows(
                listOf(
                    item(58, PreviewSamples.CUSTOMER_NAME, DUE, Money(50_900), false),
                    item(57, null, TODAY, Money(12_500), true),
                    item(56, PreviewSamples.LONG_NAME, null, PreviewSamples.LONG_AMOUNT, false),
                ),
            ),
        )
    }
}
