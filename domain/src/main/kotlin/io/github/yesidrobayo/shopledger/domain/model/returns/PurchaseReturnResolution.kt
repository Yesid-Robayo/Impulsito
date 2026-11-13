package io.github.yesidrobayo.shopledger.domain.model.returns

/**
 * Cómo se resuelve una devolución a un proveedor. Es un valor guardado en la base: su nombre no
 * cambia nunca.
 *
 * Ninguna de las dos toca la ganancia: la mercancía devuelta nunca se vendió, así que su costo no
 * llegó a ningún reporte. Solo cambian el stock, su costo y dónde queda el dinero.
 */
enum class PurchaseReturnResolution {
    /**
     * El proveedor devuelve el dinero. Si la persona lleva cuentas y elige una, entra ahí como un
     * reembolso del proveedor; sin cuenta, la devolución solo mueve el stock.
     */
    CASH_REFUND,

    /**
     * El proveedor deja el valor a favor del negocio: baja lo que se le debe, y puede quedar en
     * negativo, que es crédito con él. Exige saber cuál proveedor es.
     */
    SUPPLIER_CREDIT,
}
