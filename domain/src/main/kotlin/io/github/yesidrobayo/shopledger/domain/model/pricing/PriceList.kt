package io.github.yesidrobayo.shopledger.domain.model.pricing

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import kotlin.time.Instant

/** Cómo saca sus precios una lista. Es un valor guardado en la base: su nombre no cambia nunca. */
enum class PriceListKind {
    /** Cada producto tiene su precio escrito en la lista; sin él, toma el de la inicial. */
    FIXED,

    /** Cada precio se calcula desde otra lista con un ajuste en puntos básicos. */
    RELATIVE,
}

/**
 * De dónde salen los precios de una lista: escritos a mano, o calculados desde [Relative.baseId]
 * con [Relative.adjustment]. El tipo y sus datos viajan juntos, para que una lista relativa nunca
 * quede sin base ni sin ajuste.
 */
sealed interface PriceListPricing {
    /** El tipo que se guarda en la base. */
    val kind: PriceListKind
        get() = when (this) {
            Fixed -> PriceListKind.FIXED
            is Relative -> PriceListKind.RELATIVE
        }

    /** Precios escritos a mano, producto por producto. */
    data object Fixed : PriceListPricing

    /** Cada precio es el de la lista [baseId] con [adjustment], con signo: −15 % son −1500. */
    data class Relative(val baseId: Long, val adjustment: BasisPoints) : PriceListPricing
}

/**
 * Una lista de precios guardada.
 *
 * La inicial lleva la clave de [SystemRow.DEFAULT_PRICE_LIST], usa el precio de venta de cada
 * producto, siempre es fija y nunca se archiva ni se borra. Sin nombre propio, su texto sale del
 * recurso traducible de su clave. Las que crea la persona llevan nombre y no tienen clave.
 */
data class PriceList(
    val id: Long,
    val systemKey: String?,
    val name: String?,
    val pricing: PriceListPricing,
    val archivedAt: Instant?,
) {
    /** Si es la lista inicial, cuyo precio es el precio de venta de cada producto. */
    val isDefault: Boolean get() = systemKey == SystemRow.DEFAULT_PRICE_LIST.systemKey

    /** Si está archivada: no se ofrece en una venta nueva, pero explica lo ya registrado. */
    val isArchived: Boolean get() = archivedAt != null
}

/**
 * Lo que se guarda de una lista cuando la persona la crea o la edita, ya revisado. La inicial no se
 * edita: conserva su nombre traducido y siempre es fija.
 */
data class PriceListDetails(
    val name: String,
    val normalizedName: String,
    val pricing: PriceListPricing,
)

/**
 * Lo que la persona escribe en la hoja de una lista, tal cual, antes de revisarlo. Con
 * [PriceListKind.FIXED], [basePriceListId] y [adjustment] no se usan.
 */
data class PriceListDraft(
    val name: String,
    val kind: PriceListKind,
    val basePriceListId: Long?,
    val adjustment: BasisPoints?,
)

/** Por qué no se puede guardar lo escrito en la hoja de una lista. Cada uno va en su campo. */
enum class PriceListInputError {
    /** Una lista creada por la persona siempre tiene nombre. */
    NAME_EMPTY,

    /** El nombre pasa del largo máximo. */
    NAME_TOO_LONG,

    /** Una lista relativa necesita la lista de la que parte. */
    BASE_MISSING,

    /** Una lista relativa necesita su ajuste. */
    ADJUSTMENT_MISSING,

    /** El ajuste baja de −100 % o pasa del máximo. */
    ADJUSTMENT_OUT_OF_RANGE,
}

/**
 * Las listas tal como las muestra su pantalla: las vigentes, las archivadas, y cuáles tienen uso.
 *
 * Una lista en [inUse] ya la usa una venta, un cliente, otra lista, un precio o el historial: no se
 * elimina, solo se archiva. Las listas van en el orden en que se crearon, la inicial primero.
 */
data class PriceListsOverview(
    val active: List<PriceList>,
    val archived: List<PriceList>,
    val inUse: Set<Long>,
)
