package io.github.yesidrobayo.shopledger.ui.imports

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportDraft
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportExistingPolicy
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportPreview
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRejection
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRowIssue
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportTarget
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator

/** Los cuatro pasos del asistente, en orden. */
enum class ImportStep(val number: Int) {
    FILE(1),
    MAP(2),
    REVIEW(3),
    DONE(4),
    ;

    companion object {
        const val TOTAL: Int = 4
    }
}

/** Por qué no se pudo usar el archivo elegido: se queda en el paso 1, sin importar nada. */
sealed interface ImportFileProblem {
    /** El lector rechazó el archivo entero, antes de procesar nada. */
    data class Rejected(val reason: ImportRejection) : ImportFileProblem

    /** La primera fila no trae ningún encabezado: no hay columnas que relacionar. */
    data object NoHeader : ImportFileProblem
}

/**
 * Una columna del archivo en el paso 2: su encabezado, hasta dos ejemplos y el dato que trae, o
 * `null` si no se importa. [recognized] dice si el dato salió del encabezado o lo eligió la
 * persona; una columna sin dato y sin reconocer va primero, con su aviso.
 */
@Immutable
data class ImportColumnUi(
    val index: Int,
    val header: String,
    val examples: List<String>,
    val field: String?,
    val recognized: Boolean,
    /** Si la persona ya la tocó: una elegida a mano deja de ser `Sin relacionar`. */
    val chosen: Boolean = false,
) {
    /** Sin relacionar: ni reconocida ni elegida. */
    val unmatched: Boolean get() = this.field == null && !recognized && !chosen
}

/** Cómo terminó la importación, para el paso 4. */
data class ImportDoneUi(val created: Int, val updated: Int, val skipped: Int, val errors: Int)

/** Lo que el paso 1 dice de la moneda del negocio. */
data class ImportCurrencyUi(
    /** El código ISO de la moneda, como `XXX` mientras no se confirme. */
    val code: String,
    val confirmed: Boolean,
    /** Si ya hay montos guardados: entonces la moneda ya no cambia y no hace falta avisar. */
    val locked: Boolean,
)

/** Un aviso del asistente que no es del archivo. */
enum class ImportNotice {
    /** No se pudieron leer los datos del negocio para revisar las filas. */
    PREPARE_FAILED,

    /** No se pudo importar y nada cambió. */
    APPLY_FAILED,

    /** No se pudo importar por falta de espacio, y nada cambió. */
    APPLY_OUT_OF_SPACE,

    /** Lo que ya existe cambió desde la revisión: hay que revisar otra vez. */
    OUTDATED,

    /** La moneda no está confirmada y el archivo trae montos. */
    CURRENCY_UNCONFIRMED,

    /** No se pudo guardar el CSV con las filas con error. */
    SAVE_ERRORS_FAILED,
}

/**
 * Todo lo que muestra `Importar datos`, paso por paso. El archivo leído vive solo en memoria:
 * nunca se copia ni entra en el estado guardado, así que si Android cierra la app hay que elegirlo
 * otra vez.
 */
@Immutable
data class ImportUiState(
    val step: ImportStep = ImportStep.FILE,
    val target: ImportTarget = ImportTarget.PRODUCTS,
    val currency: ImportCurrencyUi? = null,
    // Paso 1.
    val reading: Boolean = false,
    val rowsRead: Int = 0,
    val fileProblem: ImportFileProblem? = null,
    // Paso 2.
    val fileName: String? = null,
    val rowCount: Int = 0,
    /** El separador de columnas del CSV, o `null` si es una hoja de cálculo. */
    val separator: CsvSeparator? = null,
    val decimal: DecimalSeparator = DecimalSeparator.POINT,
    val formatExpanded: Boolean = false,
    val columns: List<ImportColumnUi> = emptyList(),
    /** Los datos que se pueden elegir para una columna, en el orden en que se ofrecen. */
    val fields: List<String> = emptyList(),
    /** Los obligatorios sin columna, marcados tras tocar `Revisar filas`. */
    val missing: List<String> = emptyList(),
    /** Los datos que eligieron dos columnas, marcados tras tocar `Revisar filas`. */
    val duplicated: List<String> = emptyList(),
    /** La columna cuyo selector de dato está abierto. */
    val choosingColumn: Int? = null,
    // Paso 3.
    val preparing: Boolean = false,
    val preview: ImportPreview? = null,
    val policy: ImportExistingPolicy = ImportExistingPolicy.SKIP,
    val applying: Boolean = false,
    // Paso 4.
    val done: ImportDoneUi? = null,
    val notice: ImportNotice? = null,
    val confirmLeave: Boolean = false,
) {
    /** Si hay un archivo leído que se perdería al salir. */
    val hasFile: Boolean get() = step == ImportStep.MAP || step == ImportStep.REVIEW

    /** Si el tipo elegido lleva montos y la moneda todavía no se confirmó. */
    val needsCurrency: Boolean
        get() = target == ImportTarget.PRODUCTS && currency?.confirmed == false

    /** Los problemas de cada fila, en el orden de la hoja. */
    val issues: List<ImportRowIssue> get() = preview?.issues.orEmpty()

    /** Las primeras filas válidas, para la vista previa. */
    val previewRows: List<ImportDraft>
        get() = preview?.let { (it.ready + it.existing + it.locked).sortedBy(ImportDraft::row) }
            .orEmpty()
            .take(PREVIEW_ROWS)

    /** Cuántas filas se escriben al importar con la opción elegida. */
    val toApply: Int get() = preview?.toApply(policy) ?: 0

    companion object {
        /** Las filas de la vista previa: el resto entra igual, pero no se dibuja. */
        const val PREVIEW_ROWS: Int = 20
    }
}

/** Lo que la persona hace en el asistente. */
sealed interface ImportAction {
    data object Close : ImportAction

    data object Back : ImportAction

    data object ConfirmLeave : ImportAction

    data object DismissLeave : ImportAction

    data class ChooseTarget(val target: ImportTarget) : ImportAction

    /** `Elegir archivo`: abre el selector del sistema, que resuelve la navegación. */
    data object ChooseFile : ImportAction

    /** El selector devolvió [source], el documento elegido, o `null` si se canceló. */
    data class FileChosen(val source: String?) : ImportAction

    data object ConfirmCurrency : ImportAction

    data object ToggleFormat : ImportAction

    data class ChangeSeparator(val separator: CsvSeparator) : ImportAction

    data class ChangeDecimal(val decimal: DecimalSeparator) : ImportAction

    data class OpenColumn(val index: Int) : ImportAction

    data object DismissColumn : ImportAction

    /** La columna [index] trae [field], o no se importa con `null`. */
    data class ChooseField(val index: Int, val field: String?) : ImportAction

    data object Review : ImportAction

    data class ChoosePolicy(val policy: ImportExistingPolicy) : ImportAction

    /** `Guardar filas con error`: abre el selector para crear el archivo. */
    data object SaveErrors : ImportAction

    /** El selector devolvió [destination], el documento creado, o `null` si se canceló. */
    data class ErrorsDestination(val destination: String?) : ImportAction

    data object Apply : ImportAction

    data object ViewProducts : ImportAction

    data object ImportAnother : ImportAction

    data object DismissNotice : ImportAction
}
