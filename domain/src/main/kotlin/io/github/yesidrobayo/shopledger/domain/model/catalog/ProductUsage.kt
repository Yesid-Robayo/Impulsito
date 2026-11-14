package io.github.yesidrobayo.shopledger.domain.model.catalog

/**
 * Para qué tiene la persona un producto. Es un valor guardado en la base: su nombre no cambia
 * nunca.
 *
 * Un solo catálogo guarda lo que se vende y lo que se usa para fabricar. Un insumo no se ofrece al
 * vender, ni en cotizaciones, pedidos o el catálogo para compartir, pero se compra, se cuenta, se
 * transfiere, se ajusta y entra en recetas y producción como cualquier otro producto, con su
 * stock, su costo, sus lotes y su alerta de mínimo. Un producto con receta que se fabrica para
 * vender se vende; sus componentes pueden tener cualquier uso.
 */
enum class ProductUsage {
    /** Se vende. Es el uso de todo producto que no dice otro. */
    SALE,

    /** Solo se usa para fabricar o para el negocio: no se ofrece al vender. */
    INPUT,

    /** Se vende suelto y también va en recetas, como la harina que se vende y va en el pan. */
    BOTH,
    ;

    /** Si se ofrece al vender, cotizar, tomar pedidos y en el catálogo para compartir. */
    val isSold: Boolean get() = this != INPUT

    /** Si es un insumo: el filtro `Insumos` de la lista de productos lo muestra. */
    val isInput: Boolean get() = this != SALE
}
