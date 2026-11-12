package io.github.yesidrobayo.shopledger.ui.category

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CategoriesOverview

/** Las dos listas de la pantalla de categorías. */
enum class CategoryTab {
    PRODUCTS,
    EXPENSES,
}

/**
 * El estado de la pantalla de categorías: la lista elegida, lo leído, y lo que se abre encima: la
 * hoja de crear o renombrar y la confirmación de eliminar o archivar.
 */
@Immutable
data class CategoriesUiState(
    val tab: CategoryTab = CategoryTab.PRODUCTS,
    val content: CategoriesContent = CategoriesContent.Loading,
    val sheet: CategorySheet? = null,
    val confirm: CategoryConfirm? = null,
    val failure: CategoryFailure? = null,
    val message: CategoryMessage? = null,
)

/** Lo que muestran las listas. */
sealed interface CategoriesContent {
    /** Leyendo. El esqueleto solo aparece si tarda. */
    data object Loading : CategoriesContent

    /** No se pudo leer. Se puede reintentar. */
    data object ReadError : CategoriesContent

    /** Las categorías de productos y de gastos, con sus conteos. */
    data class Ready(val overview: CategoriesOverview) : CategoriesContent
}

/**
 * La hoja de crear o renombrar una categoría de [tab]. [editingId] es la que se renombra, o `null` al
 * crear. [initial] dice si es una categoría de gasto inicial: en blanco, vuelve a su nombre
 * traducido. [isFixed] solo existe en las de gasto.
 */
@Immutable
data class CategorySheet(
    val tab: CategoryTab,
    val editingId: Long? = null,
    val name: String = "",
    val isFixed: Boolean = false,
    val initial: Boolean = false,
    val error: CategoryNameError? = null,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
)

/** Lo que impide guardar el nombre, en su campo. */
enum class CategoryNameError {
    EMPTY,
    TOO_LONG,
    DUPLICATE,
}

/** Lo que se confirma antes de hacerlo, porque no se deshace con un toque. */
sealed interface CategoryConfirm {
    /** Eliminar una categoría de producto: sus [products] productos quedan sin categoría. */
    data class DeleteProduct(val id: Long, val name: String, val products: Int) : CategoryConfirm

    /** Eliminar una categoría de gasto sin gastos. */
    data class DeleteExpense(val id: Long, val name: String) : CategoryConfirm

    /** Archivar una categoría de gasto con gastos. */
    data class ArchiveExpense(val id: Long, val name: String) : CategoryConfirm
}

/** Lo que no se pudo hacer desde la lista, para avisarlo una vez. */
enum class CategoryFailure {
    /** Otra categoría vigente ya usa su nombre: hay que renombrar una antes de reactivarla. */
    NAME_TAKEN,

    /** La base no pudo escribir, o la categoría cambió mientras tanto. */
    NOT_SAVED,
}

/** El aviso breve que sigue a guardar una categoría. */
enum class CategoryMessage {
    SAVED,
}

/** Lo que la persona hace en la pantalla de categorías. */
sealed interface CategoriesAction {
    data class TabChange(val tab: CategoryTab) : CategoriesAction

    data object Retry : CategoriesAction

    data object NewCategory : CategoriesAction

    /** Renombrar la categoría [id] de [tab]; [name] es el nombre propio, o vacío en una inicial. */
    data class Rename(
        val tab: CategoryTab,
        val id: Long,
        val name: String,
        val isFixed: Boolean,
        val initial: Boolean,
    ) : CategoriesAction

    data class NameChange(val name: String) : CategoriesAction

    data class FixedChange(val isFixed: Boolean) : CategoriesAction

    data object Save : CategoriesAction

    data object CloseSheet : CategoriesAction

    /** Pedir confirmación de eliminar o archivar. */
    data class Ask(val confirm: CategoryConfirm) : CategoriesAction

    data object Confirm : CategoriesAction

    data object CloseConfirm : CategoriesAction

    data class Reactivate(val id: Long) : CategoriesAction

    data object FailureShown : CategoriesAction

    data object MessageShown : CategoriesAction

    /** Volver: lo resuelve la navegación. */
    data object Back : CategoriesAction
}
