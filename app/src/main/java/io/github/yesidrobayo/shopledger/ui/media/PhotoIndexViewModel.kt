package io.github.yesidrobayo.shopledger.ui.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveStoredImagesUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn

/**
 * Las imágenes anotadas de una pantalla con fotos, o `null` mientras se leen. Una lectura fallida
 * deja las filas sin foto: nunca cierra la pantalla.
 */
class PhotoIndexViewModel(observe: ObserveStoredImagesUseCase) : ViewModel() {
    val index: StateFlow<Map<Long, StoredImage>?> =
        observe()
            .catch { emit(emptyMap()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
