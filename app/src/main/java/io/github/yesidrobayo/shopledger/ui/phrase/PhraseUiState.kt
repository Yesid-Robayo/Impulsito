package io.github.yesidrobayo.shopledger.ui.phrase

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftGap
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIssue
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason

/**
 * Lo que muestra `Decir o escribir`: el campo de la frase y, debajo, en qué va.
 *
 * @param text lo escrito o dictado. Con una tarjeta a la vista, una frase corta la corrige.
 * @param textMissing se tocó `Entender` con el campo vacío: el campo lo dice.
 * @param stage en qué va la lectura.
 * @param sheet la hoja abierta para corregir un dato de la tarjeta, o `null`.
 * @param voiceReady escuchar está encendido y el teléfono tiene servicio de voz: el campo ofrece un
 *   micrófono que abre la pantalla de escuchar.
 * @param listening se pidió escuchar: la ruta muestra la pantalla de escuchar y la da por
 *   atendida. Mientras tanto, el teclado no se abre.
 * @param voiceNotice por qué la hoja quedó con el teclado en vez de la frase oída, o `null`.
 * @param cardShown cuántas veces una frase dejó una tarjeta nueva, leída o corregida: con cada una,
 *   la pantalla cierra el teclado y lleva la vista a la tarjeta. Corregirla a mano no cuenta.
 * @param registered lo que se acaba de registrar, para el mensaje: la ruta lo muestra una vez y la
 *   hoja queda lista para otra frase.
 */
@Immutable
data class PhraseUiState(
    val text: String = "",
    val textMissing: Boolean = false,
    val stage: PhraseStage = PhraseStage.Empty,
    val sheet: PhraseSheet? = null,
    val voiceReady: Boolean = false,
    val listening: Boolean = false,
    val voiceNotice: VoiceNotice? = null,
    val registered: PhraseRegistered? = null,
    val cardShown: Int = 0,
)

/** En qué va la lectura de la frase. */
sealed interface PhraseStage {
    /** Sin frase todavía: el campo con el teclado abierto y unos ejemplos. */
    data object Empty : PhraseStage

    /** Leyendo la frase: el botón trabaja y lo escrito sigue a la vista. */
    data object Reading : PhraseStage

    /** La frase no dice nada que se pueda registrar: se pide decirla de otra forma. */
    data object NotUnderstood : PhraseStage

    /** La frase mezcla dos operaciones, como vender y comprar: se pide una a la vez. */
    data object Mixed : PhraseStage

    /** No se pudo leer el catálogo del negocio. Lo escrito sigue, y se puede reintentar. */
    data object ReadFailed : PhraseStage

    /**
     * La tarjeta para confirmar. Con huecos en [PhraseCard.gaps] está incompleta: cada uno se
     * resuelve en su lugar, y `Registrar` dice qué falta en vez de registrar.
     *
     * @param registering se está registrando: el botón trabaja y no atiende otro toque.
     * @param failure por qué no se registró el último intento, o `null`.
     * @param notice lo que la última frase dejó por decidir o por saber sobre la tarjeta, o `null`.
     */
    @Immutable
    data class Card(
        val card: PhraseCard,
        val registering: Boolean = false,
        val failure: PhraseFailure? = null,
        val notice: CardNotice? = null,
    ) : PhraseStage
}

/**
 * Lo que la última frase dejó sobre la tarjeta sin cambiarla. La tarjeta sigue entera debajo:
 * ninguna frase la borra en silencio.
 */
sealed interface CardNotice {
    /**
     * La frase es otra operación, [intent] con su [reason] si es un ajuste: se pregunta si empezar
     * otra tarjeta, que descarta esta, o seguir con esta, que descarta la frase.
     */
    @Immutable
    data class OtherOperation(val intent: PhraseIntent, val reason: StockAdjustmentReason?) :
        CardNotice

    /**
     * La frase dice el tipo o la categoría de [name], un producto que ya existe: eso se cambia en
     * el producto, que se abre con su botón.
     */
    @Immutable
    data class KnownProduct(val productId: Long, val name: String) : CardNotice
}

/**
 * Una operación que se elige a mano arriba de la tarjeta: [intent], con su [reason] si es un
 * ajuste. Solo las que tienen líneas, que se conservan al cambiar.
 */
@Immutable
data class CardOperation(val intent: PhraseIntent, val reason: StockAdjustmentReason? = null)

/** Por qué no se registró la tarjeta. Nada quedó a medias en ningún caso. */
sealed interface PhraseFailure {
    /** Falta algo: se marca en la tarjeta. */
    data object Incomplete : PhraseFailure

    /**
     * La frase nueva no corrigió la tarjeta ni se entendió como otra operación: la tarjeta sigue
     * como estaba, para no perder lo ya revisado.
     */
    data object NotUnderstood : PhraseFailure

    /** La frase nueva mezcla dos operaciones: la tarjeta sigue como estaba. */
    data object Mixed : PhraseFailure

    /** El caso de uso rechazó los datos, como un producto archivado o un monto fuera de rango. */
    data object Rejected : PhraseFailure

    /** No se pudo guardar; [outOfSpace] si el teléfono no tiene espacio. */
    data class SaveFailed(val outOfSpace: Boolean) : PhraseFailure
}

/**
 * La operación que se va a registrar, lista para dibujar: los nombres ya resueltos con el catálogo
 * del negocio y los montos en `Money`.
 *
 * @param lines las líneas, en el orden dicho. En una receta son los insumos.
 * @param payment el método de pago, en una venta, una compra o un pago; `null` si no aplica.
 * @param party el cliente o el proveedor; `null` si no aplica.
 * @param category la categoría, solo en un gasto.
 * @param target el producto de una receta o el producto nuevo.
 * @param total el total dicho o, si cada línea tiene monto, su suma. En un gasto o un pago es el
 *   monto. `null` si todavía no se sabe: se calcula al registrar.
 * @param sensitive las cifras son costos: van ocultas con el modo discreto.
 * @param gaps lo que falta para poder registrar.
 * @param notices lo que la frase dejó dudoso y conviene revisar.
 * @param operations las operaciones que se eligen con un toque arriba de la tarjeta, vacía si esta
 *   operación no se cambia a mano. La elegida es la de [intent] y [reason].
 * @param inputs en una producción, lo que se dijo que llevó, con lo que dice la receta de cada uno.
 * @param updatesRecipe en una producción con receta y con insumos dichos, el interruptor
 *   `Actualizar la receta`, que nace apagado; `null` si no se muestra.
 * @param savesRecipe en una producción de algo sin receta, con insumos dichos: al registrar, lo
 *   dicho queda como su receta.
 */
@Immutable
data class PhraseCard(
    val intent: PhraseIntent,
    val reason: StockAdjustmentReason? = null,
    val lines: List<CardLine> = emptyList(),
    val payment: CardChoice? = null,
    val credit: Boolean = false,
    val party: CardParty? = null,
    val category: CardChoice? = null,
    val target: CardProduct? = null,
    val targetQuantity: QuantityMilli? = null,
    val price: Money? = null,
    val cost: Money? = null,
    val unit: CardUnit? = null,
    val total: Money? = null,
    val sensitive: Boolean = false,
    val gaps: Set<DraftGap> = emptySet(),
    val notices: List<PhraseIssue> = emptyList(),
    val operations: List<CardOperation> = emptyList(),
    val inputs: List<CardLine> = emptyList(),
    val updatesRecipe: Boolean? = null,
    val savesRecipe: Boolean = false,
)

/** Lo que dice la receta de un insumo dicho en una producción, junto a él en la tarjeta. */
sealed interface CardRecipe {
    /** La receta no lo lleva: entra solo en esta producción, salvo que se actualice la receta. */
    data object NotInRecipe : CardRecipe

    /**
     * Para esta tanda, la receta lleva [quantity] en [unit]; [same] si es lo mismo que se dijo, y
     * entonces no hay diferencia que mostrar.
     */
    @Immutable
    data class InRecipe(val quantity: QuantityMilli, val unit: CardUnit?, val same: Boolean) :
        CardRecipe
}

/**
 * Una línea de la tarjeta.
 *
 * @param quantity la cantidad en [unit], o `null` si falta.
 * @param unit la unidad dicha o, si no se dijo, la base del producto; `null` sin producto.
 * @param amount lo que suma la línea: el total dicho, o el precio dicho por la cantidad. `null` si
 *   no se dijo: sale del precio o del costo del producto al registrar.
 * @param otherOperation el producto existe pero no es de esta operación, como vender un insumo.
 * @param lastCost en una compra sin costo dicho, el último costo del producto en la unidad de la
 *   línea, para ofrecerlo con un toque. Nunca se pone solo: un costo errado daña el promedio.
 * @param recipe en un insumo dicho de una producción, lo que dice la receta de él, o `null`.
 */
@Immutable
data class CardLine(
    val index: Int,
    val product: CardProduct,
    val quantity: QuantityMilli?,
    val unit: CardUnit?,
    val unitPrice: Money?,
    val amount: Money?,
    val otherOperation: Boolean = false,
    val lastCost: Money? = null,
    val recipe: CardRecipe? = null,
)

/** El producto de una línea o de una receta. */
sealed interface CardProduct {
    /** Un producto del catálogo. */
    @Immutable
    data class Chosen(val id: Long, val name: String) : CardProduct

    /**
     * Un producto que no existe, con el nombre dicho. Si [needsCreating], hay que crearlo antes de
     * registrar, con `Crear «…»`; si no, es el producto nuevo que se crea al registrar.
     *
     * Lo demás es lo que la frase dijo de él, y con eso se crea: [usage], para qué es, o `null`
     * si no se dijo; [service], si es un servicio, sin stock; [category], el nombre de la
     * categoría que se encontró; y [newCategory], la que se dijo y no existe, que se crea con el
     * producto.
     */
    @Immutable
    data class New(
        val name: String,
        val needsCreating: Boolean,
        val usage: ProductUsage? = null,
        val service: Boolean = false,
        val category: String? = null,
        val newCategory: String? = null,
    ) : CardProduct

    /**
     * Falta elegirlo: [candidates] son los igual de posibles, y [spoken] lo que se dijo, para
     * ofrecer crearlo si [canCreate].
     */
    @Immutable
    data class Missing(
        val candidates: List<CardOption>,
        val spoken: String?,
        val canCreate: Boolean,
    ) : CardProduct
}

/** Una opción para elegir: un producto, un método, una persona o una categoría. */
@Immutable
data class CardOption(val id: Long, val name: String)

/** Un dato que se elige entre [options], con [selectedId] elegido o `null` si falta. */
@Immutable
data class CardChoice(val selectedId: Long?, val options: List<CardOption>)

/**
 * El cliente o el proveedor. [chosen] es el elegido; [candidates], los igual de posibles; y
 * [spoken], un nombre que no está en la lista. [supplier] dice cuál de los dos es.
 */
@Immutable
data class CardParty(
    val supplier: Boolean,
    val chosen: CardOption?,
    val candidates: List<CardOption>,
    val spoken: String?,
)

/**
 * Una unidad, con lo que hace falta para escribirla: el nombre que ve la persona, su símbolo y su
 * clave inicial, que traduce el símbolo de las unidades que trae la app.
 */
@Immutable
data class CardUnit(
    val id: Long,
    val name: String,
    val symbol: String?,
    val systemKey: String?,
    val kind: UnitKind,
)

/** La hoja abierta sobre la tarjeta. */
sealed interface PhraseSheet {
    /**
     * Corregir una línea: su cantidad, su precio o costo por unidad, cambiar el producto o
     * quitarla. Los textos son lo que la persona escribe; los errores se marcan al tocar `Listo`.
     * Un cambio de precio o de costo no lleva cantidad: [showsQuantity] es falso. [input] dice si es
     * un insumo dicho de una producción, y no una línea.
     */
    @Immutable
    data class Line(
        val index: Int,
        val title: String?,
        val unit: CardUnit?,
        val quantityText: String,
        val priceText: String,
        val showsPrice: Boolean,
        val showsQuantity: Boolean = true,
        val quantityError: Boolean = false,
        val priceError: Boolean = false,
        val input: Boolean = false,
    ) : PhraseSheet

    /** Escribir un monto de la tarjeta: el total, el precio o el costo. */
    @Immutable
    data class Amount(val field: AmountField, val text: String, val error: Boolean = false) :
        PhraseSheet

    /**
     * Elegir de una lista con búsqueda: un producto para [purpose], un cliente o un proveedor.
     * [items] ya viene filtrada por [query]. [canCreate] habilita `Crear «…»` con lo escrito.
     */
    @Immutable
    data class Pick(
        val purpose: PickPurpose,
        val query: String,
        val items: List<CardOption>,
        val canCreate: Boolean,
    ) : PhraseSheet
}

/** Qué se elige en la hoja de lista. */
sealed interface PickPurpose {
    /** El producto de la línea [index]. */
    data class LineProduct(val index: Int) : PickPurpose

    /** El producto del insumo dicho [index] de una producción. */
    data class InputProduct(val index: Int) : PickPurpose

    /** El producto de la receta o el producto nuevo. */
    data object Target : PickPurpose

    /** El cliente o el proveedor. */
    data object Party : PickPurpose

    /**
     * La categoría del producto por crear de la línea [index], o del producto nuevo o de la receta
     * con `null`: una del catálogo, o una nueva con lo escrito.
     */
    data class NewCategory(val index: Int?) : PickPurpose
}

/** El monto que se escribe en la hoja de monto. */
enum class AmountField {
    /** El total de la operación, o el monto de un gasto o un pago. */
    TOTAL,

    /** El precio de venta de un producto nuevo. */
    PRICE,

    /** El costo de un producto nuevo. */
    COST,
}

/** Lo que se acaba de registrar: la operación y, si tiene, su documento para deshacerla. */
@Immutable
data class PhraseRegistered(val intent: PhraseIntent, val documentId: Long?)

/**
 * Cómo terminó registrar, sin textos: la ruta lo arma con el resultado del caso de uso, y la
 * pantalla lo traduce.
 */
sealed interface PhraseOutcome {
    data class Registered(val intent: PhraseIntent, val documentId: Long?) : PhraseOutcome

    data class Incomplete(val gaps: Set<DraftGap>) : PhraseOutcome

    data object Rejected : PhraseOutcome

    data class SaveFailed(val outOfSpace: Boolean) : PhraseOutcome
}

/**
 * Con qué símbolos se leen los montos y las cantidades que la persona escribe al corregir una
 * línea. Lo arma la ruta con los formatos del negocio, así el ViewModel no conoce Android.
 */
data class PhraseInputFormat(
    val moneySymbols: NumberSymbols,
    val currencyDecimals: Int,
    val quantitySymbols: NumberSymbols,
)
