package io.github.yesidrobayo.shopledger.domain.model.settings

/** Claro u oscuro. Es un valor guardado en la base: su nombre no cambia nunca. */
enum class ThemeMode {
    /** Sigue el ajuste del teléfono. */
    SYSTEM,

    /** Siempre claro. */
    LIGHT,

    /** Siempre oscuro. */
    DARK,
}
