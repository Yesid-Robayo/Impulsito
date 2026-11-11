package io.github.yesidrobayo.shopledger.domain.model.catalog

/**
 * Lo que se guarda de una unidad cuando la persona la crea o la edita, ya revisado.
 *
 * [name] y [normalizedName] van juntos: los dos nulos en una unidad inicial que conserva su nombre
 * traducido, y los dos con valor en cualquier otra. [symbol] nulo es sin símbolo propio: una inicial
 * muestra entonces el traducido. [kind] solo agrupa al elegir, y [allowsFraction] dice si una línea
 * en esta unidad admite decimales.
 */
data class UnitDetails(
    val name: String?,
    val normalizedName: String?,
    val symbol: String?,
    val kind: UnitKind,
    val allowsFraction: Boolean,
)

/**
 * Lo que la persona escribe en la hoja de una unidad, tal cual, antes de revisarlo.
 *
 * En una unidad inicial, [name] y [symbol] en blanco conservan los textos traducidos: la unidad
 * sigue el idioma de la interfaz. Con un nombre propio, deja de seguirlo.
 */
data class UnitDraft(
    val name: String,
    val symbol: String,
    val kind: UnitKind,
    val allowsFraction: Boolean,
)

/** Por qué no se puede guardar lo escrito en la hoja de una unidad. Cada uno va en su campo. */
enum class UnitInputError {
    /** Una unidad creada por la persona siempre tiene nombre. */
    NAME_EMPTY,

    /** El nombre pasa del largo máximo. */
    NAME_TOO_LONG,

    /** El símbolo pasa del largo máximo. */
    SYMBOL_TOO_LONG,
}

/**
 * Las unidades tal como las muestra su pantalla: las vigentes, las archivadas, y cuáles tienen uso.
 *
 * Una unidad en [inUse] la usa algún producto como base, alguna conversión o alguna línea: ya no se
 * elimina, solo se archiva. Las listas van en el orden en que se crearon.
 */
data class UnitsOverview(
    val active: List<MeasureUnit>,
    val archived: List<MeasureUnit>,
    val inUse: Set<Long>,
)
