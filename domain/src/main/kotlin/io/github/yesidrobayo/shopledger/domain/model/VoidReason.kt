package io.github.yesidrobayo.shopledger.domain.model

/**
 * Por qué se anuló un documento. Es un valor guardado en la base: su nombre no cambia nunca.
 *
 * No todos los motivos valen para todos los documentos: lo decide [VoidableDocument].
 */
enum class VoidReason {
    /** Se registró mal: otra cantidad, otro precio u otro producto. */
    ENTRY_ERROR,

    /** Quien compró devolvió todo. Solo en ventas. */
    CUSTOMER_RETURN,

    /** Se devolvió todo al proveedor. Solo en compras. */
    SUPPLIER_RETURN,

    /** La persona tocó Deshacer justo después de confirmar. */
    UNDO,

    /** Otro motivo, que la persona puede explicar en la nota. */
    OTHER,
}
