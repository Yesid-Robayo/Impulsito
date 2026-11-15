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
     * con que abre, como desde el detalle de un cliente. Con [exchange], es la venta nueva de un
     * cambio: lo devuelto la paga, y nada se escribe hasta registrar el cambio.
     */
    @Serializable
    data class NewSale(
        val prefillFromSaleId: Long? = null,
        val prefillProductId: Long? = null,
        val prefillCustomerId: Long? = null,
        val exchange: SaleExchange? = null,
        val document: SaleDocumentMode? = null,
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
     * escribió en una búsqueda sin resultados, para no escribirlo dos veces. [prefillBarcode] es el
     * código que el escáner leyó y ningún producto tenía. Con [asCombo], un producto nuevo empieza
     * con `Por componentes` elegido, como desde `Nuevo combo`.
     */
    @Serializable
    data class ProductEdit(
        val productId: Long? = null,
        val prefillName: String? = null,
        val prefillBarcode: String? = null,
        val asCombo: Boolean = false,
    ) : AppNavKey

    /**
     * El detalle de un producto; con [openCostSheet], abre de una vez la hoja de corregir el costo, y
     * con [openPriceSheet], la de cambiar el precio.
     */
    @Serializable
    data class ProductDetail(
        val productId: Long,
        val openCostSheet: Boolean = false,
        val openPriceSheet: Boolean = false,
    ) : AppNavKey

    /**
     * La actualización masiva; con [priceListId], abre con esa lista elegida; con [pendingReview],
     * con los productos de `Costo nuevo sin revisar`; con [review], con los de `Revisar`; y con
     * [referenceRate], en `Por cotización`.
     */
    @Serializable
    data class BulkPriceUpdate(
        val priceListId: Long? = null,
        val pendingReview: Boolean = false,
        val review: Boolean = false,
        val referenceRate: Boolean = false,
    ) : AppNavKey

    @Serializable
    data class Inventory(val filter: InventoryFilter = InventoryFilter.LOW_STOCK) : AppNavKey

    /**
     * El ajuste de stock de un producto. Con [lotId], la salida sale de ese lote; con
     * [expiredOut], abre en `Salida` con el motivo `Vencido`, como `Dar salida` desde un lote.
     */
    @Serializable
    data class StockAdjustment(
        val productId: Long,
        val lotId: Long? = null,
        val expiredOut: Boolean = false,
    ) : AppNavKey

    @Serializable
    data object Suppliers : AppNavKey

    @Serializable
    data class SupplierDetail(val supplierId: Long) : AppNavKey

    /**
     * Una compra nueva. Desde la reposición sugerida llega con [prefillLines], cada una con su
     * cantidad, y con la ubicación que se miraba en [prefillLocationId].
     */
    @Serializable
    data class Purchase(
        val prefillProductId: Long? = null,
        val prefillFromPurchaseId: Long? = null,
        val prefillSupplierId: Long? = null,
        val prefillLines: List<PurchasePrefillLine> = emptyList(),
        val prefillLocationId: Long? = null,
    ) : AppNavKey

    @Serializable
    data class Expenses(
        val prefillFromExpenseId: Long? = null,
        /** Abrir con la hoja del gasto nuevo, como desde el atajo `Gasto` de Inicio. */
        val startNew: Boolean = false,
        /**
         * Con [startNew], el gasto recurrente cuyo vencimiento actual se registra: la hoja abre
         * con sus datos puestos.
         */
        val recurringExpenseId: Long? = null,
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
    data class SupplierReturn(val purchaseId: Long? = null, val supplierId: Long? = null) :
        AppNavKey

    /** El detalle de una devolución a proveedor, con su anulación. */
    @Serializable
    data class SupplierReturnDetail(val purchaseReturnId: Long) : AppNavKey

    @Serializable
    data object PriceLists : AppNavKey

    @Serializable
    data object ReferenceCurrency : AppNavKey

    @Serializable
    data object Units : AppNavKey

    @Serializable
    data class Variants(val productId: Long) : AppNavKey

    /** La receta de un combo: sus componentes, lo que cuesta y cuánto alcanza. */
    @Serializable
    data class Components(val productId: Long) : AppNavKey

    /** Los extras que se ofrecen al vender un producto. */
    @Serializable
    data class Extras(val productId: Long) : AppNavKey

    /** La lista de combos y recetas, que abre el centro `Negocio`. */
    @Serializable
    data object Combos : AppNavKey

    @Serializable
    data class Scanner(val purpose: ScanPurpose) : AppNavKey

    @Serializable
    data object CustomFields : AppNavKey

    @Serializable
    data class Labels(val onlyRepriced: Boolean = false) : AppNavKey

    /**
     * Los lotes y vencimientos; con [productId], solo los de ese producto, y [lotId] abre su hoja.
     */
    @Serializable
    data class Lots(val productId: Long? = null, val lotId: Long? = null) : AppNavKey

    @Serializable
    data object Locations : AppNavKey

    /** El formulario de una transferencia de stock, desde [fromLocationId] si se sabe. */
    @Serializable
    data class NewStockTransfer(val fromLocationId: Long? = null) : AppNavKey

    @Serializable
    data class StockCount(val sessionId: Long? = null) : AppNavKey

    @Serializable
    data object Production : AppNavKey

    /** Una producción nueva, con el producto ya elegido si llega desde su receta. */
    @Serializable
    data class ProductionForm(val productId: Long? = null) : AppNavKey

    @Serializable
    data class Replenishment(val supplierId: Long? = null) : AppNavKey

    @Serializable
    data object ImportData : AppNavKey

    @Serializable
    data object ReceiptTemplate : AppNavKey

    /** El comprobante de una venta, para verlo y compartirlo. */
    @Serializable
    data class SaleReceipt(val saleId: Long) : AppNavKey

    @Serializable
    data object Printers : AppNavKey

    @Serializable
    data class Quotes(val quoteId: Long? = null) : AppNavKey

    @Serializable
    data class Orders(val orderId: Long? = null) : AppNavKey

    /**
     * Los pendientes de facturar. Con [saleId], abre la hoja `Registro externo` de esa venta, como
     * desde el detalle de la venta.
     */
    @Serializable
    data class PendingFiscal(val saleId: Long? = null) : AppNavKey

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

    /**
     * La ayuda, o un artículo suyo por su clave estable. [topicKey] dice desde qué pantalla se
     * llegó por su enlace de ayuda, o es `null` si se abrió desde Ajustes o Negocio.
     */
    @Serializable
    data class Help(val articleKey: String? = null, val topicKey: String? = null) : AppNavKey

    @Serializable
    data object BackupEncryption : AppNavKey

    @Serializable
    data object LocalCopies : AppNavKey

    /**
     * `Almacenamiento y borrado`. [fromAndroid] dice que llegó desde el botón de los ajustes de
     * Android, para recordar mirar las copias antes de borrar.
     */
    @Serializable
    data class StorageAndDeletion(val fromAndroid: Boolean = false) : AppNavKey

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

/**
 * Lo que la devolución deja elegido para un cambio: la venta [saleId], cada línea con lo que se
 * devuelve en milésimas de su unidad base y si vuelve al inventario, el crédito [credit] en la unidad
 * menor de la moneda del negocio y el método [creditMethodId] con que se cobró la venta, que paga la
 * parte que alcanza. Solo números y banderas: la nota viaja aparte, fuera de la pila guardada.
 */
@Serializable
data class SaleExchange(
    val saleId: Long,
    val lines: List<SaleExchangeLine>,
    val credit: Long,
    val creditMethodId: Long?,
)

/** Una línea devuelta en un cambio. */
@Serializable
data class SaleExchangeLine(val saleLineId: Long, val quantityMilli: Long, val restock: Boolean)

/**
 * Una línea con que se abre una compra: el producto y la cantidad en milésimas de [unitId], o de
 * su unidad base si es nula. El costo no viene: lo escribe la persona con lo que le cobran.
 */
@Serializable
data class PurchasePrefillLine(
    val productId: Long,
    val quantityMilli: Long,
    val unitId: Long? = null,
)

/**
 * Para qué se abre la venta nueva, si no es para vender sin más.
 *
 * - [NewQuote] arma una cotización nueva: la misma pantalla, sin pagos y con `Guardar cotización`.
 * - [EditQuote] abre una cotización abierta con sus líneas, para guardarla encima.
 * - [FromQuote] abre una venta con las líneas de una cotización: al registrarla, la cotización queda
 *   convertida en la misma transacción. Con [useCurrentPrices], las líneas toman el precio de hoy.
 * - [NewOrder] arma un pedido nuevo: la misma pantalla, sin pagos y con `Guardar pedido`.
 * - [EditOrder] abre un pedido sin entregar con sus líneas, para guardarlo encima.
 * - [FromOrder] abre la venta de la entrega de un pedido: sus líneas al precio acordado, su cliente
 *   y su envío. Al registrarla, el pedido queda entregado en la misma transacción.
 */
@Serializable
sealed interface SaleDocumentMode {
    @Serializable
    data object NewQuote : SaleDocumentMode

    @Serializable
    data class EditQuote(val quoteId: Long) : SaleDocumentMode

    @Serializable
    data class FromQuote(val quoteId: Long, val useCurrentPrices: Boolean) : SaleDocumentMode

    @Serializable
    data object NewOrder : SaleDocumentMode

    @Serializable
    data class EditOrder(val orderId: Long) : SaleDocumentMode

    @Serializable
    data class FromOrder(val orderId: Long) : SaleDocumentMode
}
