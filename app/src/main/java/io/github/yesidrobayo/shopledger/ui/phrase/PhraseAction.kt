package io.github.yesidrobayo.shopledger.ui.phrase

/** Lo que la persona puede hacer en `Decir o escribir`. */
sealed interface PhraseAction {
    /** Escribe o dicta en el campo. */
    data class TextChange(val text: String) : PhraseAction

    /**
     * `Entender`: lee la frase. Con una tarjeta a la vista, primero intenta aplicarla como una
     * corrección; si no lo es, la lee como una frase nueva.
     */
    data object Understand : PhraseAction

    /** La pantalla de escuchar oyó [text]: se lee igual que al tocar `Entender`. */
    data class Heard(val text: String) : PhraseAction

    /** La `X` de la barra: cierra la hoja. Lo que no se registró se descarta. */
    data object Close : PhraseAction

    /**
     * `Vender a mano`: cambia la hoja por una venta nueva de siempre. El botón central de la barra
     * es `Decir`, así que vender a mano también se ofrece aquí.
     */
    data object SellByHand : PhraseAction

    /** `¿No funciona el micrófono?`: abre la ayuda con los pasos para activar el dictado. */
    data object MicHelp : PhraseAction

    /** El micrófono del campo: abre la pantalla de escuchar. */
    data object Listen : PhraseAction

    /**
     * La pantalla de escuchar terminó sin frase: queda el teclado, con [notice] si hay algo que
     * decir.
     */
    data class ListenEnded(val notice: VoiceNotice?) : PhraseAction

    /** `Abrir ajustes del teléfono` en el aviso del micrófono sin permiso. */
    data object OpenMicSettings : PhraseAction

    /** `Reintentar` tras una lectura fallida. */
    data object Retry : PhraseAction

    /** `Empezar de nuevo`: descarta la tarjeta sin registrar nada. */
    data object StartOver : PhraseAction

    /** Elige [productId] para la línea [index], entre los candidatos o en la lista. */
    data class ChooseProduct(val index: Int, val productId: Long) : PhraseAction

    /** `Crear «…»` en la línea [index]: el producto se crea al registrar. */
    data class CreateProduct(val index: Int, val name: String) : PhraseAction

    /** `Elegir otro` en la línea [index]: abre la lista de productos. */
    data class PickProduct(val index: Int) : PhraseAction

    /** Toca la línea [index] para corregirla. */
    data class OpenLine(val index: Int) : PhraseAction

    data class LineQuantityChange(val text: String) : PhraseAction

    data class LinePriceChange(val text: String) : PhraseAction

    /** `Listo` en la hoja de la línea. */
    data object SaveLine : PhraseAction

    /** `Quitar de la tarjeta` en la hoja de la línea. */
    data object RemoveLine : PhraseAction

    /** Elige el método de pago. */
    data class ChoosePayment(val methodId: Long) : PhraseAction

    /** `Último costo: …` en una línea de compra sin costo: lo toma como costo por unidad. */
    data class UseLastCost(val index: Int) : PhraseAction

    /** Elige el cliente o el proveedor; `null` lo quita. */
    data class ChooseParty(val id: Long?) : PhraseAction

    /** `Cambiar` en el cliente o el proveedor: abre la lista. */
    data object PickParty : PhraseAction

    data class ChooseCategory(val categoryId: Long) : PhraseAction

    /** `Cambiar` o `Elegir` en el producto de la receta o del producto nuevo. */
    data object PickTarget : PhraseAction

    /** Elige el producto de la receta. */
    data class ChooseTarget(val productId: Long) : PhraseAction

    /** Crea el producto de la receta o el producto nuevo con [name]. */
    data class CreateTarget(val name: String) : PhraseAction

    /** Toca un monto de la tarjeta para escribirlo. */
    data class OpenAmount(val field: AmountField) : PhraseAction

    data class AmountChange(val text: String) : PhraseAction

    /** `Listo` en la hoja del monto. */
    data object SaveAmount : PhraseAction

    /**
     * `Elegir categoría` en el producto por crear de la línea [index], o en el producto nuevo o el
     * de la receta con `null`: abre la lista de categorías.
     */
    data class PickNewCategory(val index: Int?) : PhraseAction

    /** Pone la categoría [categoryId] del catálogo al producto por crear de [index]. */
    data class ChooseNewCategory(val index: Int?, val categoryId: Long) : PhraseAction

    /**
     * `Crear «…»` en la lista de categorías: el producto por crear de [index] va en una categoría
     * nueva, [name], que se crea con él.
     */
    data class NameNewCategory(val index: Int?, val name: String) : PhraseAction

    /** Escribe en la búsqueda de la hoja de lista. */
    data class QueryChange(val query: String) : PhraseAction

    /** Cierra la hoja abierta sin cambiar nada. */
    data object CloseSheet : PhraseAction

    /** `Registrar`. */
    data object Register : PhraseAction

    /** La ruta ya mostró el mensaje de lo registrado. */
    data object RegisteredShown : PhraseAction
}
