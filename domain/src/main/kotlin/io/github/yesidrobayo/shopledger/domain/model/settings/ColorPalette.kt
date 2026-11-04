package io.github.yesidrobayo.shopledger.domain.model.settings

/**
 * La paleta de colores que eligió la persona. Es un valor guardado en la base: su nombre no cambia
 * nunca, y el orden de las constantes es el del selector.
 *
 * Solo cambia colores, y es independiente de [ThemeMode]: cada paleta tiene su versión clara y su
 * versión oscura. El ícono de la app no cambia con ella.
 */
enum class ColorPalette {
    /** Índigo con mostaza: seria y confiable. La inicial. */
    INDIGO_MUSTARD,

    /** Baya con miel: magenta profundo. */
    BERRY_HONEY,

    /** Frambuesa con oro: más viva. */
    RASPBERRY_GOLD,

    /** Vino con champaña: sobria. */
    WINE_CHAMPAGNE,

    /** Ciruela con coral: cálida. */
    PLUM_CORAL,

    /** Bosque con miel: verde de dinero. */
    FOREST_HONEY,

    /** Carmín con oro: rojo clásico. */
    CARMINE_GOLD,

    /** Granate con miel: rojo profundo y cálido. */
    GARNET_HONEY,

    /** Rojo vivo con crema: la más llamativa. */
    VIVID_RED_CREAM,
}
