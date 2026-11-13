package io.github.yesidrobayo.shopledger.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.UndoBulkPriceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.sale.UndoSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleUseCase
import io.github.yesidrobayo.shopledger.ui.product.BulkPriceUndo
import io.github.yesidrobayo.shopledger.ui.sale.SaleUndo

/**
 * Deshacer la venta recién registrada, armado con la base de la app y su alcance, que dura lo que
 * la app: la venta nueva ya se cerró cuando la persona toca `Deshacer`.
 *
 * La base ya está abierta, porque la venta se acaba de registrar. Si fue al rescate en el
 * intermedio, deshacer falla y la venta sigue activa.
 */
@Composable
fun rememberSaleUndo(): SaleUndo {
    val container = (LocalContext.current.applicationContext as ShopLedgerApp).container
    return remember(container) {
        SaleUndo(container.applicationScope) { saleId ->
            when (val access = container.data.open()) {
                is DataAccess.Ready -> {
                    val ports = access.ports
                    val void =
                        VoidSaleUseCase(
                            transactions = ports.transactions,
                            settings = ports.settings,
                            products = ports.products,
                            sales = ports.sales,
                            ledger = ports.stockLedger,
                            clock = container.clock,
                            alerts = ports.lowStockAlerts,
                            customerLedger = ports.customerLedger,
                        )
                    UndoSaleUseCase(void)(saleId)
                }

                is DataAccess.Rescue -> null
            }
        }
    }
}

/**
 * Deshacer la actualización masiva recién aplicada, armado como el de la venta: la pantalla ya se
 * cerró cuando la persona toca `Deshacer` en el aviso.
 */
@Composable
fun rememberBulkPriceUndo(): BulkPriceUndo {
    val container = (LocalContext.current.applicationContext as ShopLedgerApp).container
    return remember(container) {
        BulkPriceUndo(container.applicationScope) { batchId ->
            when (val access = container.data.open()) {
                is DataAccess.Ready -> {
                    val ports = access.ports
                    UndoBulkPriceUseCase(
                        transactions = ports.transactions,
                        products = ports.products,
                        systemRows = ports.systemRows,
                        priceHistory = ports.priceHistory,
                        calendar = BusinessCalendarSource(ports.settings),
                        clock = container.clock,
                    )(batchId)
                }

                is DataAccess.Rescue -> null
            }
        }
    }
}
