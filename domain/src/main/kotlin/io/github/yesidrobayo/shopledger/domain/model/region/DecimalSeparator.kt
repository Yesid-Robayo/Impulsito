package io.github.yesidrobayo.shopledger.domain.model.region

/**
 * El separador decimal del formato regional del negocio, como lo leen las hojas de cálculo: un punto
 * o una coma.
 *
 * Solo hay dos porque una hoja de cálculo solo reconoce esos dos al abrir un CSV. Lo decide el
 * formato regional del negocio, nunca el idioma de la interfaz, y lo obtiene `:app` con ICU: aquí
 * llega ya resuelto.
 */
enum class DecimalSeparator(
    /** El carácter que separa la parte entera de la fracción. */
    val char: Char,
) {
    /** `12.34`. */
    POINT('.'),

    /** `12,34`. */
    COMMA(','),
}
