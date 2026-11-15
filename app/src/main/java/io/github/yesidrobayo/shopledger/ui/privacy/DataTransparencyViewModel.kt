package io.github.yesidrobayo.shopledger.ui.privacy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.EncryptionStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.SecondDestinationView
import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyConsentStatus
import io.github.yesidrobayo.shopledger.domain.model.settings.AppPreferences
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * `Qué datos hay y adónde van`: lee el estado real, sin red, cada vez que la pantalla se abre o
 * vuelve a primer plano. Cada dato se lee aparte: uno que falla dice que no está disponible, y los
 * demás siguen. Nunca se muestra un valor anterior en su lugar.
 *
 * Aceptar la política nueva desde aquí es lo mismo que en el diálogo de cambios: queda en el
 * disco, y las copias que esperaban vuelven a programarse. No aceptar nunca bloquea nada.
 *
 * @param consentStatus la aceptación de la política, de `consents.json`.
 * @param acceptPolicy acepta las versiones vigentes; `false` si el teléfono no la guardó.
 * @param describeDestination el destino conectado, leído del teléfono, o nulo sin destino.
 * @param encryptionStatus si las copias salen cifradas.
 * @param secondDestination la carpeta del segundo destino.
 * @param preferences el bloqueo y la búsqueda de versión, de nuevo cada vez que cambian.
 * @param computation donde se leen los archivos, fuera del hilo principal.
 */
class DataTransparencyViewModel(
    private val consentStatus: suspend () -> PolicyConsentStatus,
    private val acceptPolicy: suspend () -> Boolean,
    private val describeDestination: suspend () -> BackupDestinationInfo?,
    private val encryptionStatus: suspend () -> EncryptionStatus,
    private val secondDestination: suspend () -> SecondDestinationView,
    preferences: Flow<AppPreferences>,
    private val computation: CoroutineContext,
) : ViewModel() {
    private val facts = MutableStateFlow<LocalFacts?>(null)
    private val extra = MutableStateFlow(Extra())
    private var reading: Job? = null

    /** Las preferencias, o nulo mientras no llegan. Si no se pueden leer, lo dice. */
    private val settings: Flow<Fact<AppPreferences>?> =
        preferences
            .map<AppPreferences, Fact<AppPreferences>?> { Fact.Known(it) }
            .onStart { emit(null) }
            .catch { emit(Fact.Unavailable) }

    val uiState: StateFlow<DataTransparencyUiState> =
        combine(facts, settings, extra) { local, prefs, more ->
            DataTransparencyUiState(
                loading = local == null || prefs == null,
                consent = local?.consent ?: Fact.Unavailable,
                destination = local?.destination ?: Fact.Unavailable,
                encryptionOn = local?.encryptionOn ?: Fact.Unavailable,
                second = local?.second ?: Fact.Unavailable,
                appLock = prefs.mapFact { it.appLockEnabled },
                updateCheck = prefs.mapFact { it.updateCheckEnabled },
                noticeDismissed = more.dismissed,
                accepting = more.accepting,
                acceptFailed = more.acceptFailed,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            DataTransparencyUiState(),
        )

    init {
        refresh()
    }

    fun onAction(action: DataTransparencyAction) {
        when (action) {
            DataTransparencyAction.AcceptPolicy -> accept()

            DataTransparencyAction.DecideLater -> extra.update { it.copy(dismissed = true) }

            DataTransparencyAction.Resumed -> refresh()

            DataTransparencyAction.MessageShown -> extra.update { it.copy(acceptFailed = false) }

            // Las resuelven la pantalla y la navegación.
            DataTransparencyAction.Back,
            DataTransparencyAction.OpenPrivacy,
            DataTransparencyAction.OpenTerms,
            -> Unit
        }
    }

    /** Vuelve a leer lo local. Una lectura nueva reemplaza a la que seguía en curso. */
    private fun refresh() {
        reading?.cancel()
        reading =
            viewModelScope.launch {
                val read =
                    withContext(computation) {
                        LocalFacts(
                            consent = readFact { consentStatus() },
                            destination = readFact { describeDestination() },
                            encryptionOn =
                                readFact { encryptionStatus().isOn() }.nullAsUnavailable(),
                            second = readFact { secondDestination().toFact() }.nullAsUnavailable(),
                        )
                    }
                facts.value = read
            }
    }

    private fun accept() {
        if (extra.value.accepting) return
        extra.update { it.copy(accepting = true, acceptFailed = false) }
        viewModelScope.launch {
            val saved =
                try {
                    acceptPolicy()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
            extra.update { it.copy(accepting = false, acceptFailed = !saved) }
            if (saved) refresh()
        }
    }

    /** Lo que se lee de archivos del teléfono, todo junto. */
    private data class LocalFacts(
        val consent: Fact<PolicyConsentStatus>,
        val destination: Fact<BackupDestinationInfo?>,
        val encryptionOn: Fact<Boolean>,
        val second: Fact<SecondFact>,
    )

    private data class Extra(
        val dismissed: Boolean = false,
        val accepting: Boolean = false,
        val acceptFailed: Boolean = false,
    )

    private companion object {
        /** Lo que sigue viva la lectura al rotar la pantalla. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * El valor de [block], o que no está disponible si falla. Leer el estado nunca cierra la pantalla.
 * El valor puede ser nulo de verdad, como el destino cuando no hay ninguno.
 */
private suspend fun <T> readFact(block: suspend () -> T): Fact<T> = try {
    Fact.Known(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    Fact.Unavailable
}

/** Un valor nulo que significa que no se entendió lo leído: no está disponible. */
private fun <T : Any> Fact<T?>.nullAsUnavailable(): Fact<T> = when (this) {
    is Fact.Known -> value?.let { Fact.Known(it) } ?: Fact.Unavailable
    Fact.Unavailable -> Fact.Unavailable
}

/** El dato de las preferencias, o que no está disponible mientras no llegan o si fallaron. */
private fun <T> Fact<AppPreferences>?.mapFact(pick: (AppPreferences) -> T): Fact<T> =
    when (this) {
        is Fact.Known -> Fact.Known(pick(value))
        Fact.Unavailable, null -> Fact.Unavailable
    }

/**
 * Si las copias salen cifradas. Con el código perdido el cifrado sigue activo: las copias se
 * detienen y nunca salen en claro. Sin código en este teléfono, está apagado aquí. Un estado que
 * no se pudo leer no se adivina.
 */
private fun EncryptionStatus.isOn(): Boolean? = when (this) {
    is EncryptionStatus.On, EncryptionStatus.KeyUnavailable -> true
    is EncryptionStatus.Off, EncryptionStatus.NoCodeHere -> false
    EncryptionStatus.Unknown -> null
}

/** El segundo destino, o nulo si su archivo no se entiende. */
private fun SecondDestinationView.toFact(): SecondFact? = when (this) {
    SecondDestinationView.None -> SecondFact.None
    is SecondDestinationView.Connected -> SecondFact.Folder(folderName)
    SecondDestinationView.Unreadable -> null
}
