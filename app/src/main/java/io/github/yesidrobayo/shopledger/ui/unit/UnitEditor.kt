package io.github.yesidrobayo.shopledger.ui.unit

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitInputError
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.rule.catalog.UnitFormRules
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveUnitResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Los textos de las unidades en el idioma de la interfaz: el nombre y el símbolo que se ven, propios
 * o traducidos. El ViewModel los necesita para no repetir el nombre traducido de una inicial, y para
 * saber si la persona dejó el de una inicial tal como estaba.
 */
interface UnitNames {
    fun name(unit: MeasureUnit): String

    fun symbol(unit: MeasureUnit): String?
}

/** Por qué no se guarda el nombre de una unidad. */
enum class UnitNameProblem {
    EMPTY,
    TOO_LONG,
    DUPLICATE,
}

/**
 * La hoja de una unidad mientras se escribe.
 *
 * [editingId] nulo es una unidad nueva. Con [isInitial], es una de las iniciales: [name] y [symbol]
 * empiezan con su texto traducido, y si se dejan así siguen el idioma de la interfaz. [saveFailed]
 * dice si el último guardado falló, y con qué: lo escrito siempre se conserva.
 */
@Immutable
data class UnitEditor(
    val editingId: Long?,
    val isInitial: Boolean,
    val name: String,
    val symbol: String,
    val kind: UnitKind,
    val allowsFraction: Boolean,
    val translatedName: String? = null,
    val translatedSymbol: String? = null,
    val nameProblem: UnitNameProblem? = null,
    val symbolTooLong: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: SaveFailure? = null,
)

/** Un guardado que no se pudo hacer: [outOfSpace] si fue por falta de espacio. */
@Immutable
data class SaveFailure(val outOfSpace: Boolean)

/** Lo que la persona hace en la hoja de una unidad. */
sealed interface UnitEditorAction {
    data class Name(val text: String) : UnitEditorAction

    data class Symbol(val text: String) : UnitEditorAction

    data class Kind(val kind: UnitKind) : UnitEditorAction

    data class AllowsFraction(val allows: Boolean) : UnitEditorAction

    data object Save : UnitEditorAction

    data object Dismiss : UnitEditorAction
}

/**
 * La hoja de crear o editar una unidad, para la pantalla de unidades y para el formulario de un
 * producto, que crea una sin salir de él.
 *
 * Los errores aparecen al guardar, nunca mientras se escribe, y lo escrito no se pierde por un error.
 * El nombre no puede repetir el de otra vigente, tampoco el traducido de una inicial: dos `Caja` en la
 * misma lista no se distinguirían. Guardar corre una sola vez a la vez.
 *
 * @param activeUnits las unidades vigentes en este momento.
 * @param onSaved recibe el id de la unidad guardada, con la hoja ya cerrada.
 */
class UnitEditorModel(
    private val scope: CoroutineScope,
    private val names: UnitNames,
    private val activeUnits: () -> List<MeasureUnit>,
    private val save: suspend (Long?, UnitDraft) -> SaveUnitResult,
    private val onSaved: (Long) -> Unit,
) {
    private val state = MutableStateFlow<UnitEditor?>(null)
    val editor: StateFlow<UnitEditor?> = state.asStateFlow()

    /** Abre la hoja vacía, para una unidad nueva con [name] ya escrito. */
    fun openNew(name: String = "") {
        state.value =
            UnitEditor(
                editingId = null,
                isInitial = false,
                name = name,
                symbol = "",
                kind = UnitKind.COUNT,
                allowsFraction = false,
            )
    }

    /** Abre la hoja con [unit] para editarla. */
    fun openEdit(unit: MeasureUnit) {
        val initial = unit.systemKey != null
        val shownName = names.name(unit)
        val shownSymbol = names.symbol(unit).orEmpty()
        state.value =
            UnitEditor(
                editingId = unit.id,
                isInitial = initial,
                name = shownName,
                symbol = shownSymbol,
                kind = unit.kind,
                allowsFraction = unit.allowsFraction,
                translatedName = if (initial && unit.name == null) shownName else null,
                translatedSymbol = if (initial && unit.symbol == null) shownSymbol else null,
            )
    }

    fun onAction(action: UnitEditorAction) {
        when (action) {
            is UnitEditorAction.Name ->
                change { copy(name = action.text, nameProblem = null, saveFailed = null) }

            is UnitEditorAction.Symbol ->
                change { copy(symbol = action.text, symbolTooLong = false, saveFailed = null) }

            is UnitEditorAction.Kind -> change { copy(kind = action.kind) }

            is UnitEditorAction.AllowsFraction -> change { copy(allowsFraction = action.allows) }

            UnitEditorAction.Save -> save()

            UnitEditorAction.Dismiss -> state.value = null
        }
    }

    private fun change(block: UnitEditor.() -> UnitEditor) {
        state.update { it?.block() }
    }

    private fun save() {
        val current = state.value ?: return
        if (current.saving) return
        val draft = draftOf(current)
        val duplicate = draft.name.isNotBlank() && clashes(draft.name, current.editingId)
        if (duplicate) {
            change { copy(nameProblem = UnitNameProblem.DUPLICATE) }
            return
        }
        change { copy(saving = true, saveFailed = null) }
        scope.launch {
            val result = save(current.editingId, draft)
            if (result is SaveUnitResult.Saved) {
                state.value = null
                onSaved(result.id)
            } else {
                change { copy(saving = false).withProblem(result) }
            }
        }
    }

    /**
     * Lo escrito, como lo guarda el dominio. El nombre o el símbolo traducidos de una inicial, sin
     * cambiar, van en blanco: así la unidad sigue el idioma de la interfaz.
     */
    private fun draftOf(editor: UnitEditor): UnitDraft {
        val name = if (editor.name.trim() == editor.translatedName) "" else editor.name
        val symbol = if (editor.symbol.trim() == editor.translatedSymbol) "" else editor.symbol
        return UnitDraft(name, symbol, editor.kind, editor.allowsFraction)
    }

    /** Si otra vigente ya se ve con ese nombre, propio o traducido, sin mayúsculas ni tildes. */
    private fun clashes(name: String, editingId: Long?): Boolean {
        val wanted = TextNormalizer.normalize(name.trim())
        return activeUnits().any {
            it.id != editingId && TextNormalizer.normalize(names.name(it)) == wanted
        }
    }

    private fun UnitEditor.withProblem(result: SaveUnitResult): UnitEditor = when (result) {
        is SaveUnitResult.Saved -> this

        is SaveUnitResult.Invalid ->
            copy(
                nameProblem =
                    when {
                        UnitInputError.NAME_EMPTY in result.errors -> UnitNameProblem.EMPTY
                        UnitInputError.NAME_TOO_LONG in result.errors -> UnitNameProblem.TOO_LONG
                        else -> null
                    },
                symbolTooLong = UnitInputError.SYMBOL_TOO_LONG in result.errors,
            )

        SaveUnitResult.DuplicateName -> copy(nameProblem = UnitNameProblem.DUPLICATE)

        // Se archivó o se eliminó mientras se editaba: no se guardó nada, y lo escrito sigue.
        SaveUnitResult.NotFound -> copy(saveFailed = SaveFailure(outOfSpace = false))

        is SaveUnitResult.StorageFailed ->
            copy(saveFailed = SaveFailure(result.failure.isOutOfSpace))
    }

    companion object {
        /** Largo máximo del nombre, para el mensaje de error. */
        const val NAME_MAX: Int = UnitFormRules.NAME_MAX

        /** Largo máximo del símbolo, para el mensaje de error. */
        const val SYMBOL_MAX: Int = UnitFormRules.SYMBOL_MAX
    }
}
