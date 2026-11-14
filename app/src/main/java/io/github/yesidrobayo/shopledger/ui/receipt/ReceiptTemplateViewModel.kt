package io.github.yesidrobayo.shopledger.ui.receipt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.catalog.ImageImport
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.settings.ReceiptLogoChange
import io.github.yesidrobayo.shopledger.domain.model.settings.ReceiptSettings
import io.github.yesidrobayo.shopledger.domain.model.settings.ReceiptTemplateField
import io.github.yesidrobayo.shopledger.domain.model.settings.ReceiptTemplateInput
import io.github.yesidrobayo.shopledger.domain.usecase.settings.MAX_RECEIPT_TEXT_LENGTH
import io.github.yesidrobayo.shopledger.domain.usecase.settings.SaveReceiptTemplateResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Lo que la plantilla lee de los ajustes: lo que lleva el comprobante y el nombre del negocio. */
data class ReceiptTemplateSource(val receipt: ReceiptSettings, val businessName: String?)

/**
 * La plantilla del comprobante: logo, datos del negocio y pie, con su vista previa.
 *
 * Lee la plantilla guardada una vez y la deja editar. El nombre del negocio se sigue leyendo,
 * porque se cambia en los ajustes. Guardar corre en una transacción; si falla, lo escrito se
 * conserva y se puede intentar otra vez. El largo de cada texto se avisa mientras se escribe.
 *
 * @param observeSource la plantilla guardada y el nombre del negocio.
 * @param loadImage la imagen guardada de un id de la tabla de imágenes, o `null` si ya no está.
 * @param importLogo trae la imagen elegida, recodificada y sin metadatos.
 * @param save guarda la plantilla.
 */
class ReceiptTemplateViewModel(
    private val observeSource: () -> Flow<ReceiptTemplateSource>,
    private val loadImage: suspend (Long) -> StoredImage?,
    private val importLogo: suspend (String) -> ImageImport,
    private val save: suspend (ReceiptTemplateInput) -> SaveReceiptTemplateResult,
) : ViewModel() {
    private val form = MutableStateFlow<Form?>(null)
    private val readFailed = MutableStateFlow(false)

    private val businessName: Flow<String?> = observeSource()
        .map { it.businessName }
        // Sin el nombre, la plantilla se edita igual: el comprobante sale sin él.
        .catch { emit(null) }

    val uiState: StateFlow<ReceiptTemplateUiState> =
        combine(form, readFailed, businessName) { editing, failed, name ->
            when {
                editing != null -> editing.toUiState(name)
                failed -> ReceiptTemplateUiState.ReadFailed
                else -> ReceiptTemplateUiState.Loading
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            ReceiptTemplateUiState.Loading,
        )

    init {
        load()
    }

    fun onAction(action: ReceiptTemplateAction) {
        when (action) {
            is ReceiptTemplateAction.DetailsChanged -> edit {
                it.copy(details = action.text, saveFailed = false)
            }

            is ReceiptTemplateAction.FooterChanged -> edit {
                it.copy(footer = action.text, saveFailed = false)
            }

            is ReceiptTemplateAction.PickLogo -> pickLogo(action.source)

            ReceiptTemplateAction.RemoveLogo -> edit {
                it.copy(logo = ReceiptLogoChange.Remove, logoImage = null, logoProblem = null)
            }

            ReceiptTemplateAction.Save -> saveTemplate()

            ReceiptTemplateAction.Leave -> edit {
                if (it.changed) it.copy(confirmLeave = true) else it.copy(left = true)
            }

            ReceiptTemplateAction.ConfirmLeave -> edit {
                it.copy(confirmLeave = false, left = true)
            }

            ReceiptTemplateAction.DismissLeave -> edit { it.copy(confirmLeave = false) }

            ReceiptTemplateAction.Retry -> load()

            ReceiptTemplateAction.SavedShown -> edit { it.copy(saved = false) }

            // Lo resuelve la ruta: abre los ajustes.
            ReceiptTemplateAction.OpenBusinessName -> Unit
        }
    }

    private fun load() {
        readFailed.value = false
        viewModelScope.launch {
            val loaded = flow<Form?> {
                val receipt = observeSource().first().receipt
                emit(
                    Form(
                        savedDetails = receipt.businessDetails.orEmpty(),
                        savedFooter = receipt.footerText.orEmpty(),
                        logoImage = receipt.logoMediaId?.let { loadImage(it) },
                        details = receipt.businessDetails.orEmpty(),
                        footer = receipt.footerText.orEmpty(),
                    ),
                )
            }.catch { emit(null) }.first()
            if (loaded == null) readFailed.value = true else form.value = loaded
        }
    }

    private fun pickLogo(source: String) {
        if (form.value?.importingLogo != false) return
        edit { it.copy(importingLogo = true, logoProblem = null) }
        viewModelScope.launch {
            val imported = importLogo(source)
            edit { current ->
                when (imported) {
                    is ImageImport.Imported -> current.copy(
                        importingLogo = false,
                        logo = ReceiptLogoChange.Use(imported.image),
                        logoImage = imported.image,
                    )

                    ImageImport.Unreadable ->
                        current.copy(importingLogo = false, logoProblem = LogoProblem.UNREADABLE)

                    ImageImport.OutOfSpace ->
                        current.copy(importingLogo = false, logoProblem = LogoProblem.OUT_OF_SPACE)
                }
            }
        }
    }

    private fun saveTemplate() {
        val current = form.value ?: return
        if (current.saving || current.importingLogo) return
        if (current.tooLong.isNotEmpty()) return
        edit { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            val input = ReceiptTemplateInput(current.logo, current.details, current.footer)
            when (save(input)) {
                SaveReceiptTemplateResult.Saved -> edit {
                    // Lo guardado pasa a ser la base: salir ya no pregunta.
                    it.copy(
                        saving = false,
                        saved = true,
                        logo = ReceiptLogoChange.Keep,
                        savedLogo = it.logoImage,
                        savedDetails = input.businessDetails.trim(),
                        savedFooter = input.footerText.trim(),
                    )
                }

                // El largo ya se avisa al escribir: si la base lo rechaza igual, el campo lo
                // sigue marcando.
                is SaveReceiptTemplateResult.TooLong -> edit { it.copy(saving = false) }

                is SaveReceiptTemplateResult.Failed -> edit {
                    it.copy(saving = false, saveFailed = true)
                }
            }
        }
    }

    private fun edit(change: (Form) -> Form) = form.update { current -> current?.let(change) }

    /**
     * Lo que se edita y la base contra la que se compara. [logo] es lo que pasará con el logo al
     * guardar, y [logoImage] el que se ve. [savedLogo] es el guardado, o `null` si no había.
     */
    private data class Form(
        val savedDetails: String,
        val savedFooter: String,
        val logoImage: StoredImage?,
        val details: String,
        val footer: String,
        val savedLogo: StoredImage? = logoImage,
        val logo: ReceiptLogoChange = ReceiptLogoChange.Keep,
        val importingLogo: Boolean = false,
        val logoProblem: LogoProblem? = null,
        val saving: Boolean = false,
        val saveFailed: Boolean = false,
        val confirmLeave: Boolean = false,
        val saved: Boolean = false,
        val left: Boolean = false,
    ) {
        val tooLong: Set<ReceiptTemplateField>
            get() = buildSet {
                if (details.isTooLong()) add(ReceiptTemplateField.BUSINESS_DETAILS)
                if (footer.isTooLong()) add(ReceiptTemplateField.FOOTER)
            }

        val changed: Boolean
            get() = logoImage?.sha256 != savedLogo?.sha256 ||
                details.trim() != savedDetails ||
                footer.trim() != savedFooter

        fun toUiState(name: String?) = ReceiptTemplateUiState.Editing(
            businessName = name?.trim()?.takeIf { it.isNotEmpty() },
            logo = logoImage,
            details = details,
            footer = footer,
            tooLong = tooLong,
            importingLogo = importingLogo,
            logoProblem = logoProblem,
            saving = saving,
            saveFailed = saveFailed,
            changed = changed,
            confirmLeave = confirmLeave,
            saved = saved,
            left = left,
        )

        /** Si pasa del largo que acepta la base, en caracteres y sin los bordes en blanco. */
        private fun String.isTooLong(): Boolean {
            val text = trim()
            return text.codePointCount(0, text.length) > MAX_RECEIPT_TEXT_LENGTH
        }
    }

    private companion object {
        /** Lo que sigue viva la lectura al rotar la pantalla. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
