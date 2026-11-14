package io.github.yesidrobayo.shopledger.ui.imports

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportApplyResult
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportExistingPolicy
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportFileRead
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportPreview
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRejection
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportSheet
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportTarget
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.port.ImportFileReader
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.imports.ColumnMatch
import io.github.yesidrobayo.shopledger.domain.rule.imports.ImportColumnMatcher
import io.github.yesidrobayo.shopledger.domain.rule.imports.ImportFileFormat
import io.github.yesidrobayo.shopledger.domain.rule.imports.ImportMappingRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lo que necesita el asistente para leer, revisar y aplicar. Cada pieza llega armada, así las
 * pruebas usan dobles.
 */
class ImportTools(
    /** Lee el documento elegido, como entrada no confiable. */
    val reader: ImportFileReader,
    /** Reconoce las columnas por su encabezado, en cualquier idioma. */
    val matcher: ImportColumnMatcher,
    /** Arma la vista previa con lo que ya existe en el negocio, sin escribir. */
    val prepare:
    suspend (ImportSheet, ImportTarget, List<String?>, DecimalSeparator) -> ImportPreview,
    /** Aplica las filas válidas en una sola transacción. */
    val apply: suspend (ImportPreview, ImportExistingPolicy) -> ImportApplyResult,
    /** Escribe [content] en el documento [destination] que creó el selector. */
    val writeErrors: suspend (destination: String, content: String) -> Boolean,
)

/**
 * `Importar datos`, en cuatro pasos: elegir el archivo, relacionar sus columnas, revisar las filas
 * e importar. Nada se escribe en la base hasta tocar `Importar`, y entonces va todo junto.
 *
 * - **El archivo vive solo en memoria.** No se copia ni entra en el estado guardado: el
 *   identificador del documento tampoco se guarda. Si Android cierra la app, se elige otra vez.
 * - **La vista previa se rehace sola.** Cada vez que cambia una columna o el separador decimal, y
 *   la relación está completa, se vuelve a revisar el archivo; la revisión anterior se cancela.
 * - **Lo desconocido va a relacionar columnas.** Una columna que no se reconoce queda sin
 *   relacionar, primero en la lista, y la persona elige qué trae o que no se importe.
 *
 * @param currency la moneda del negocio, para el paso 1.
 * @param preferredSeparator el separador de columnas de los ajustes, que desempata al detectar.
 * @param defaultDecimal lee el separador decimal del formato regional del negocio.
 */
class ImportViewModel(
    currency: Flow<ImportCurrencyUi>,
    private val preferredSeparator: suspend () -> CsvSeparator,
    private val defaultDecimal: suspend () -> DecimalSeparator,
    private val tools: ImportTools,
    private val savedState: SavedStateHandle,
    private val computation: CoroutineDispatcher,
) : ViewModel() {
    private val state = MutableStateFlow(
        ImportUiState(
            target = savedState.get<String>(TARGET)
                ?.let { stored -> ImportTarget.entries.firstOrNull { it.name == stored } }
                ?: ImportTarget.PRODUCTS,
        ),
    )
    val uiState: StateFlow<ImportUiState> = state.asStateFlow()

    private var source: String? = null
    private var sheet: ImportSheet? = null
    private var readJob: Job? = null
    private var previewJob: Job? = null
    private var reviewWhenReady = false

    init {
        viewModelScope.launch {
            // Sin ajustes legibles, el paso 1 no muestra la moneda: no impide elegir el archivo.
            currency.catch { }.collect { read -> state.update { it.copy(currency = read) } }
        }
    }

    fun onAction(action: ImportAction) {
        when (action) {
            is ImportAction.ChooseTarget -> chooseTarget(action.target)

            is ImportAction.FileChosen -> action.source?.let { read(it, separator = null) }

            ImportAction.ToggleFormat ->
                state.update { it.copy(formatExpanded = !it.formatExpanded) }

            is ImportAction.ChangeSeparator -> changeSeparator(action.separator)

            is ImportAction.ChangeDecimal -> changeDecimal(action.decimal)

            is ImportAction.OpenColumn -> state.update { it.copy(choosingColumn = action.index) }

            ImportAction.DismissColumn -> state.update { it.copy(choosingColumn = null) }

            is ImportAction.ChooseField -> chooseField(action.index, action.field)

            ImportAction.Review -> review()

            is ImportAction.ChoosePolicy -> state.update { it.copy(policy = action.policy) }

            is ImportAction.ErrorsDestination -> action.destination?.let(::saveErrors)

            ImportAction.Apply -> apply()

            ImportAction.ImportAnother -> restart()

            ImportAction.Close -> leave()

            ImportAction.Back -> back()

            ImportAction.DismissLeave -> state.update { it.copy(confirmLeave = false) }

            ImportAction.DismissNotice -> state.update { it.copy(notice = null) }

            // Salir, el selector, la moneda y la lista de productos los resuelve la navegación.
            ImportAction.ConfirmLeave,
            ImportAction.ChooseFile,
            ImportAction.SaveErrors,
            ImportAction.ConfirmCurrency,
            ImportAction.ViewProducts,
            -> Unit
        }
    }

    /** Si cerrar sale de la pantalla sin preguntar: no hay un archivo leído que se perdería. */
    fun closesDirectly(): Boolean = !state.value.hasFile

    /** Si atrás sale de la pantalla: en el paso 1 o al terminar. */
    fun backLeaves(): Boolean = when (state.value.step) {
        ImportStep.FILE, ImportStep.DONE -> true
        ImportStep.MAP, ImportStep.REVIEW -> false
    }

    /** El nombre sugerido del CSV con las filas con error: el del archivo, si se conoce. */
    fun errorsFileName(): String? = state.value.fileName

    private fun chooseTarget(target: ImportTarget) {
        if (state.value.step != ImportStep.FILE) return
        savedState[TARGET] = target.name
        state.update { it.copy(target = target, fileProblem = null) }
    }

    private fun read(chosen: String, separator: CsvSeparator?) {
        readJob?.cancel()
        previewJob?.cancel()
        source = chosen
        state.update { it.copy(reading = true, rowsRead = 0, fileProblem = null, notice = null) }
        readJob = viewModelScope.launch {
            val result = try {
                tools.reader.read(chosen, separator, preferredSeparator()) { rows ->
                    state.update { it.copy(rowsRead = rows) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Un fallo inesperado del lector se trata como un archivo que no se pudo leer.
                ImportFileRead.Rejected(ImportRejection.UNREADABLE)
            }
            opened(result)
        }
    }

    private suspend fun opened(result: ImportFileRead) {
        when (result) {
            is ImportFileRead.Rejected -> {
                sheet = null
                state.update {
                    it.copy(
                        step = ImportStep.FILE,
                        reading = false,
                        fileProblem = ImportFileProblem.Rejected(result.reason),
                    )
                }
            }

            is ImportFileRead.Read -> {
                val read = result.sheet
                if (read.header.all { it.isBlank() }) {
                    sheet = null
                    state.update {
                        it.copy(
                            step = ImportStep.FILE,
                            reading = false,
                            fileProblem = ImportFileProblem.NoHeader,
                        )
                    }
                    return
                }
                sheet = read
                val decimal = ImportFileFormat.decimalOf(read, defaultDecimal())
                val target = state.value.target
                val matches = tools.matcher.match(read.header, target)
                val columns = read.header.mapIndexed { index, header ->
                    val match = matches[index]
                    ImportColumnUi(
                        index = index,
                        header = header.trim(),
                        examples = read.rows.asSequence()
                            .map { it.cell(index).trim() }
                            .filter { it.isNotEmpty() }
                            .take(EXAMPLES)
                            .toList(),
                        field = (match as? ColumnMatch.Field)?.key,
                        recognized = match !is ColumnMatch.Unmatched,
                    )
                }
                state.update {
                    it.copy(
                        step = ImportStep.MAP,
                        reading = false,
                        fileName = result.fileName,
                        rowCount = read.rows.size,
                        separator = read.separator,
                        decimal = decimal,
                        columns = columns,
                        fields = target.fields.map { field -> field.key },
                        missing = emptyList(),
                        duplicated = emptyList(),
                        preview = null,
                        policy = ImportExistingPolicy.SKIP,
                    )
                }
                refreshPreview()
            }
        }
    }

    private fun changeSeparator(separator: CsvSeparator) {
        val chosen = source ?: return
        if (state.value.separator == separator) return
        read(chosen, separator)
    }

    private fun changeDecimal(decimal: DecimalSeparator) {
        if (state.value.decimal == decimal) return
        state.update { it.copy(decimal = decimal) }
        refreshPreview()
    }

    private fun chooseField(index: Int, field: String?) {
        state.update { current ->
            current.copy(
                choosingColumn = null,
                columns = current.columns.map { column ->
                    if (column.index != index) column else column.copy(field = field, chosen = true)
                },
            )
        }
        val check = check()
        // Lo marcado se corrige al elegir: deja de marcarse en cuanto queda bien.
        state.update {
            it.copy(
                missing = it.missing.filter { key -> key in check.missing },
                duplicated = it.duplicated.filter { key -> key in check.duplicated },
            )
        }
        refreshPreview()
    }

    private fun check() =
        ImportMappingRules.check(state.value.columns.map { it.field }, state.value.target)

    private fun refreshPreview() {
        previewJob?.cancel()
        val read = sheet ?: return
        val check = check()
        if (!check.isComplete) {
            state.update { it.copy(preview = null, preparing = false) }
            return
        }
        val current = state.value
        val columns = current.columns.map { it.field }
        state.update { it.copy(preparing = true) }
        previewJob = viewModelScope.launch {
            val preview = try {
                withContext(computation) {
                    tools.prepare(read, current.target, columns, current.decimal)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            state.update {
                it.copy(
                    preparing = false,
                    preview = preview,
                    notice = if (preview == null) ImportNotice.PREPARE_FAILED else it.notice,
                    step = if (preview != null && reviewWhenReady) ImportStep.REVIEW else it.step,
                )
            }
            reviewWhenReady = false
        }
    }

    private fun review() {
        val check = check()
        if (!check.isComplete) {
            state.update { it.copy(missing = check.missing, duplicated = check.duplicated) }
            return
        }
        val current = state.value
        when {
            current.preview != null -> state.update { it.copy(step = ImportStep.REVIEW) }

            current.preparing -> reviewWhenReady = true

            else -> {
                reviewWhenReady = true
                refreshPreview()
            }
        }
    }

    private fun apply() {
        val current = state.value
        val preview = current.preview ?: return
        if (current.applying || current.toApply == 0) return
        state.update { it.copy(applying = true, notice = null) }
        viewModelScope.launch {
            val result = tools.apply(preview, current.policy)
            when (result) {
                is ImportApplyResult.Applied -> {
                    sheet = null
                    source = null
                    state.update {
                        it.copy(
                            step = ImportStep.DONE,
                            applying = false,
                            done = ImportDoneUi(
                                created = result.created,
                                updated = result.updated,
                                skipped = result.skipped,
                                errors = preview.errorRows,
                            ),
                        )
                    }
                }

                ImportApplyResult.CurrencyUnconfirmed -> failed(ImportNotice.CURRENCY_UNCONFIRMED)

                ImportApplyResult.Outdated -> {
                    failed(ImportNotice.OUTDATED)
                    refreshPreview()
                }

                is ImportApplyResult.StorageFailed -> failed(
                    if (result.failure == StorageFailure.DISK_FULL) {
                        ImportNotice.APPLY_OUT_OF_SPACE
                    } else {
                        ImportNotice.APPLY_FAILED
                    },
                )
            }
        }
    }

    private fun failed(notice: ImportNotice) {
        state.update { it.copy(applying = false, notice = notice) }
    }

    private fun saveErrors(destination: String) {
        val read = sheet ?: return
        val rows = state.value.issues.map { it.row }.toSet()
        if (rows.isEmpty()) return
        viewModelScope.launch {
            val separator = read.separator ?: preferredSeparator()
            val content = withContext(computation) {
                ImportFileFormat.errorRowsCsv(read, rows, separator)
            }
            val saved = try {
                tools.writeErrors(destination, content)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!saved) state.update { it.copy(notice = ImportNotice.SAVE_ERRORS_FAILED) }
        }
    }

    private fun leave() {
        if (state.value.hasFile) state.update { it.copy(confirmLeave = true) }
    }

    private fun back() {
        when (state.value.step) {
            ImportStep.REVIEW -> state.update { it.copy(step = ImportStep.MAP, notice = null) }
            ImportStep.MAP -> leave()
            ImportStep.FILE, ImportStep.DONE -> Unit
        }
    }

    private fun restart() {
        readJob?.cancel()
        previewJob?.cancel()
        sheet = null
        source = null
        state.update { ImportUiState(target = it.target, currency = it.currency) }
    }

    private companion object {
        const val TARGET = "import.target"

        /** Los ejemplos que se muestran de cada columna. */
        const val EXAMPLES = 2
    }
}
