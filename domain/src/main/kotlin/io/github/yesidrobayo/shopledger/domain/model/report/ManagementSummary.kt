package io.github.yesidrobayo.shopledger.domain.model.report

import io.github.yesidrobayo.shopledger.domain.model.ProfitSummary
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportTable
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxEstimate

/**
 * El `Resumen de gestión` de un período: lo que suele pedir quien lleva la contabilidad, como
 * material de apoyo. Nunca es un libro ni una declaración.
 *
 * Sus cifras salen de las mismas sumas y reglas que `Números`, así que cuadran con esa pantalla en
 * el mismo período: ventas sin impuesto menos lo devuelto, ganancia en ventas, gastos con daños y
 * pérdidas y costo de cobrar, y ganancia final.
 *
 * @property period el mes o el rango del resumen, con sus dos extremos.
 * @property profit ventas, costo, ganancias y gastos del período.
 * @property tax el impuesto estimado, o `null` si el negocio no cobra impuesto: entonces la fila
 *   no aparece, ni en pantalla ni en el PDF.
 * @property methods lo cobrado por método de pago, de mayor a menor, todos.
 * @property salesWithoutCost cuántas ventas tienen alguna línea vendida sin costo: la ganancia
 *   puede estar inflada, y se avisa sin impedir generar.
 * @property hasMovements si el período tuvo algún movimiento: una venta, una devolución, un gasto,
 *   un ajuste, una comisión o una compra. Sin ninguno, no hay nada que generar.
 * @property contents lo que se puede incluir, además del PDF, en el orden en que se muestra.
 */
data class ManagementSummary(
    val period: ReportPeriod,
    val profit: ProfitSummary,
    val tax: TaxEstimate?,
    val methods: List<PaymentMethodTotal>,
    val salesWithoutCost: Long,
    val hasMovements: Boolean,
    val contents: List<SummaryContent>,
)

/**
 * Lo que el resumen puede llevar además del PDF, que siempre va. Cada uno es un grupo de archivos
 * CSV del período, con los mismos archivos y columnas que la exportación completa.
 */
enum class SummaryContent {
    /** Ventas, sus líneas, sus pagos y las devoluciones de clientes con sus líneas. */
    SALES_AND_PAYMENTS,

    /** Compras, sus líneas, las devoluciones a proveedores con sus líneas, y los gastos. */
    PURCHASES_AND_EXPENSES,

    /** Lo que había de cada producto al cerrar el período. Solo con movimientos de stock. */
    CLOSING_INVENTORY,

    /** Los clientes y los proveedores. Solo si el negocio tiene alguno. */
    CUSTOMERS_AND_SUPPLIERS,
}

/**
 * Lo que hay en la base para ofrecer en un período.
 *
 * @property hasPurchases si hay compras activas con fecha en el período: también son movimientos.
 * @property hasInventory si algún producto tiene movimientos de stock hasta el fin del período.
 * @property hasParties si hay algún cliente o proveedor sin archivar.
 */
data class SummaryAvailability(
    val hasPurchases: Boolean,
    val hasInventory: Boolean,
    val hasParties: Boolean,
) {
    companion object {
        /** Una base sin compras, sin stock y sin clientes ni proveedores. */
        val NONE = SummaryAvailability(
            hasPurchases = false,
            hasInventory = false,
            hasParties = false,
        )
    }
}

/** Un archivo CSV del paquete del resumen. */
sealed interface SummaryFile {
    /** Un archivo de la exportación completa, limitado a las fechas del período. */
    data class Table(val table: CsvExportTable) : SummaryFile

    /**
     * Lo que había de cada producto al terminar el último día del período: la suma de su libro de
     * stock hasta esa fecha de negocio. Sus columnas son [CLOSING_INVENTORY_COLUMNS].
     */
    data object ClosingInventory : SummaryFile

    companion object {
        /** La clave estable del archivo del inventario al cierre. */
        const val CLOSING_INVENTORY_KEY = "closing_inventory"

        /** Las columnas del inventario al cierre, con las claves de la exportación completa. */
        val CLOSING_INVENTORY_COLUMNS: List<String> = listOf("product", "base_unit", "on_hand")
    }
}
