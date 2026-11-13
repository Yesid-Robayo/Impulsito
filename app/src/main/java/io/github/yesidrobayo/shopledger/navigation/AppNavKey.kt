package io.github.yesidrobayo.shopledger.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Cada pantalla a la que se puede navegar, con sus argumentos. La pila es una lista de estas claves,
 * y se guarda serializada: sobrevive a que Android cierre el proceso.
 *
 * Los argumentos son ID numéricos y banderas, nunca textos del negocio: la pila guardada no lleva
 * datos de la persona. Dos pantallas no son claves:
 * - el rescate reemplaza toda la navegación cuando la base no abre, y nunca entra en la pila;
 * - el bloqueo envuelve toda la navegación, y al desbloquear la pila sigue intacta.
 *
 * Los nombres guardados no se cambian: una pila restaurada los busca por nombre.
 */
@Serializable
sealed interface AppNavKey : NavKey {
    /** Primer uso: idioma, región, bienvenida y protección. */
    @Serializable
    data object Onboarding : AppNavKey

    /** Inicio: el resumen del día. Raíz de su pestaña y fondo de toda la pila. */
    @Serializable
    data object Home : AppNavKey

    /**
     * Nueva venta. Es una acción que se abre encima, no un destino. [prefillCustomerId] es el cliente
     * con que abre, como desde el detalle de un cliente.
     */
    @Serializable
    data class NewSale(
        val prefillFromSaleId: Long? = null,
        val prefillProductId: Long? = null,
        val prefillCustomerId: Long? = null,
    ) : AppNavKey

    @Serializable
    data class SaleDetail(val saleId: Long) : AppNavKey

    /** Registros: raíz de su pestaña, con ventas, gastos y compras. */
    @Serializable
    data object SaleHistory : AppNavKey

    /** Productos: raíz de su pestaña, con productos, inventario y proveedores. */
    @Serializable
    data object ProductList : AppNavKey

    /**
     * Crear un producto si [productId] es nulo, o editarlo. [prefillName] es lo que la persona ya
     * escribió en una búsqueda sin resultados, para no escribirlo dos veces.
     */
    @Serializable
    data class ProductEdit(val productId: Long? = null, val prefillName: String? = null) :
        AppNavKey

    /** El detalle de un producto; con [openCostSheet], abre de una vez la hoja de corregir el costo. */
    @Serializable
    data class ProductDetail(val productId: Long, val openCostSheet: Boolean = false) : AppNavKey

    /** La actualización masiva; con [priceListId], abre con esa lista elegida. */
    @Serializable
    data class BulkPriceUpdate(val priceListId: Long? = null) : AppNavKey

    @Serializable
    data class Inventory(val filter: InventoryFilter = InventoryFilter.LOW_STOCK) : AppNavKey

    @Serializable
    data class StockAdjustment(val productId: Long) : AppNavKey

    @Serializable
    data object Suppliers : AppNavKey

    @Serializable
    data class SupplierDetail(val supplierId: Long) : AppNavKey

    @Serializable
    data class Purchase(
        val prefillProductId: Long? = null,
        val prefillFromPurchaseId: Long? = null,
        val prefillSupplierId: Long? = null,
    ) : AppNavKey

    @Serializable
    data class Expenses(
        val prefillFromExpenseId: Long? = null,
        /** Abrir con la hoja del gasto nuevo, como desde el atajo `Gasto` de Inicio. */
        val startNew: Boolean = false,
    ) : AppNavKey

    /** Números: raíz de su pestaña. */
    @Serializable
    data object Statistics : AppNavKey

    @Serializable
    data object Settings : AppNavKey

    @Serializable
    data object Backup : AppNavKey

    /**
     * El historial de compras. Desde un proveedor llega filtrado por él con [supplierId], y desde
     * `Sin proveedor` con [withoutSupplier].
     */
    @Serializable
    data class PurchaseHistory(
        val supplierId: Long? = null,
        val withoutSupplier: Boolean = false,
    ) : AppNavKey

    @Serializable
    data class PurchaseDetail(val purchaseId: Long) : AppNavKey

    @Serializable
    data object Categories : AppNavKey

    @Serializable
    data class RegionalSettings(val firstRun: Boolean = false) : AppNavKey

    @Serializable
    data object LanguageSettings : AppNavKey

    @Serializable
    data object TaxSettings : AppNavKey

    @Serializable
    data object PaymentMethods : AppNavKey

    /** Crear un método de pago si [methodId] es nulo, o editarlo. */
    @Serializable
    data class PaymentMethodEdit(val methodId: Long? = null) : AppNavKey

    /** El cobro lee la venta en curso de un estado de la actividad: no lleva argumentos. */
    @Serializable
    data object Checkout : AppNavKey

    @Serializable
    data object HeldSales : AppNavKey

    @Serializable
    data class Customers(val pickForSale: Boolean = false) : AppNavKey

    @Serializable
    data class CustomerDetail(val customerId: Long) : AppNavKey

    @Serializable
    data object LoyaltyProgram : AppNavKey

    @Serializable
    data class MoneyAccounts(val accountId: Long? = null) : AppNavKey

    @Serializable
    data object Payables : AppNavKey

    /** El libro de un proveedor en cuentas por pagar, con sus pagos. */
    @Serializable
    data class PayableDetail(val supplierId: Long) : AppNavKey

    @Serializable
    data object CashRegister : AppNavKey

    @Serializable
    data class SaleReturn(val saleId: Long) : AppNavKey

    @Serializable
    data class SupplierReturn(val purchaseId: Long? = null) : AppNavKey

    @Serializable
    data object PriceLists : AppNavKey

    @Serializable
    data object ReferenceCurrency : AppNavKey

    @Serializable
    data object Units : AppNavKey

    @Serializable
    data class Variants(val productId: Long) : AppNavKey

    @Serializable
    data class Components(val productId: Long) : AppNavKey

    @Serializable
    data class Scanner(val purpose: ScanPurpose) : AppNavKey

    @Serializable
    data object CustomFields : AppNavKey

    @Serializable
    data class Labels(val onlyRepriced: Boolean = false) : AppNavKey

    @Serializable
    data class Lots(val productId: Long? = null) : AppNavKey

    @Serializable
    data object Locations : AppNavKey

    @Serializable
    data class StockCount(val sessionId: Long? = null) : AppNavKey

    @Serializable
    data object Production : AppNavKey

    @Serializable
    data class Replenishment(val supplierId: Long? = null) : AppNavKey

    @Serializable
    data object ImportData : AppNavKey

    @Serializable
    data object ReceiptTemplate : AppNavKey

    @Serializable
    data object Printers : AppNavKey

    @Serializable
    data class Quotes(val quoteId: Long? = null) : AppNavKey

    @Serializable
    data class Orders(val orderId: Long? = null) : AppNavKey

    @Serializable
    data object PendingFiscal : AppNavKey

    @Serializable
    data object ShareableCatalog : AppNavKey

    @Serializable
    data object ManagementSummary : AppNavKey

    @Serializable
    data object GoalsAndLimits : AppNavKey

    @Serializable
    data object RecurringExpenses : AppNavKey

    @Serializable
    data object PriceIndex : AppNavKey

    /** La ayuda, o un artículo suyo por su clave estable. */
    @Serializable
    data class Help(val articleKey: String? = null) : AppNavKey

    @Serializable
    data object BackupEncryption : AppNavKey

    @Serializable
    data object LocalCopies : AppNavKey

    @Serializable
    data object StorageAndDeletion : AppNavKey

    @Serializable
    data object DataTransparency : AppNavKey

    @Serializable
    data object UpdatesAndApkCheck : AppNavKey

    @Serializable
    data object AuditLog : AppNavKey

    @Serializable
    data object CloudAccountSecurity : AppNavKey

    /** Negocio: las herramientas del día a día que no caben en la barra. */
    @Serializable
    data object BusinessHub : AppNavKey

    @Serializable
    data class BackupDestinationChoice(val firstRun: Boolean = false) : AppNavKey

    /** Conectar un servicio WebDAV, opcionalmente desde una de las sugerencias, por su clave estable. */
    @Serializable
    data class WebDavConnect(val suggestionKey: String? = null) : AppNavKey

    @Serializable
    data object DocumentSlotsSetup : AppNavKey
}

/** El filtro con que se abre el inventario: los chips de esa pantalla. */
@Serializable
enum class InventoryFilter {
    /** Por agotarse, el de siempre. */
    LOW_STOCK,

    /** Lo que pide decidir un precio. */
    REVIEW,

    /** Lotes por vencer. */
    EXPIRING,

    /** Stock negativo: así llega desde el aviso de Inicio. */
    NEGATIVE_STOCK,

    /** Todo el inventario. */
    ALL,
}

/** Desde dónde se abre el escáner, que decide qué pasa con cada lectura. */
@Serializable
enum class ScanPurpose {
    /** Agrega el producto a la venta en curso. */
    SALE,

    /** Busca el producto en la lista. */
    PRODUCT_SEARCH,

    /** Agrega el producto a la compra en curso. */
    PURCHASE,

    /** Llena el código del producto que se edita. */
    PRODUCT_BARCODE,

    /** Cuenta el producto en un conteo físico. */
    STOCK_COUNT,
}
