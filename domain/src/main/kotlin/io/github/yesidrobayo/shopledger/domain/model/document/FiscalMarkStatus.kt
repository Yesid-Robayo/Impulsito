package io.github.yesidrobayo.shopledger.domain.model.document

/**
 * Lo que la persona anotó de una venta respecto del sistema que use fuera de la app para sus
 * registros. Es un valor guardado en la base: su nombre no cambia nunca.
 *
 * La app no emite, envía ni valida ningún documento: solo recuerda lo que la persona marcó. Una
 * venta sin marca cuenta como [PENDING].
 */
enum class FiscalMarkStatus {
    /** Falta registrarla fuera de la app. */
    PENDING,

    /** La persona ya la registró en el otro sistema. */
    RECORDED,

    /** La persona decidió que esa venta no necesita registro. */
    NOT_NEEDED,
}
