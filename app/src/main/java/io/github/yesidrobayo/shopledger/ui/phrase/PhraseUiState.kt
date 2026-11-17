package io.github.yesidrobayo.shopledger.ui.phrase

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
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
 * @param voiceReady la voz directa está encendida y el teléfono tiene su servicio: el campo ofrece
 *   dictar con él.
 * @param dictate se pidió abrir el servicio de voz: la ruta lo abre y lo da por atendido.
 * @param voiceFailed el servicio de voz no abrió: el campo lo dice y queda el teclado.
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
    val dictate: Boolean = false,
    val voiceFailed: Boolean = false,
    val registered: PhraseRegistered? = null,
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
     */
    @Immutable
    data class Card(
        val card: PhraseCard,
        val registering: Boolean = false,
        val failure: PhraseFailure? = null,
    ) : PhraseStage
}

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
)

/**
 * Una línea de la tarjeta.
 *
 * @param quantity la cantidad en [unit], o `null` si falta.
 * @param unit la unidad dicha o, si no se dijo, la base del producto; `null` sin producto.
 * @param amount lo que suma la línea: el total dicho, o el precio dicho por la cantidad. `null` si
 *   no se dijo: sale del precio o del costo del producto al registrar.
 * @param otherOperation el producto existe pero no es de esta operación, como vender un insumo.
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
)

/** El producto de una línea o de una receta. */
sealed interface CardProduct {
    /** Un producto del catálogo. */
    @Immutable
    data class Chosen(val id: Long, val name: String) : CardProduct

    /**
     * Un producto que no existe, con el nombre dicho. Si [needsCreating], hay que crearlo antes de
     * registrar, con `Crear «…»`; si no, es el producto nuevo que se crea al registrar.
     */
    @Immutable
    data class New(val name: String, val needsCreating: Boolean) : CardProduct

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
     * Un cambio de precio o de costo no lleva cantidad: [showsQuantity] es falso.
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

    /** El producto de la receta o el producto nuevo. */
    data object Target : PickPurpose

    /** El cliente o el proveedor. */
    data object Party : PickPurpose
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
