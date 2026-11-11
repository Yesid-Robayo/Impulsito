package io.github.yesidrobayo.shopledger.domain.usecase.onboarding

import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult

/** En qué paso del primer uso conviene seguir, según lo que ya quedó guardado. */
enum class OnboardingResume {
    /** Falta confirmar la región y la moneda: se empieza por el idioma. */
    LANGUAGE,

    /** La región ya se confirmó: se sigue en la bienvenida, sin repetir lo elegido. */
    WELCOME,

    /** El primer uso ya terminó, por ejemplo porque una copia restaurada lo traía. */
    DONE,
}

/**
 * Dónde retomar el primer uso tras cerrar la app a mitad: el idioma vive fuera de la base, y la
 * región confirmada deja la moneda fijada, así que no hace falta guardar el paso aparte.
 */
class ResumeOnboardingUseCase(private val settings: AppSettingsRepository) {
    suspend operator fun invoke(): OnboardingResume {
        val current = settings.get()
        return when {
            current.onboardingCompletedAt != null -> OnboardingResume.DONE
            current.regional.currency.code.isConfirmed -> OnboardingResume.WELCOME
            else -> OnboardingResume.LANGUAGE
        }
    }
}

/** Resultado de [CompleteOnboardingUseCase]. */
sealed interface CompleteOnboardingResult {
    data object Completed : CompleteOnboardingResult

    /** La base no pudo escribir, por [failure]: el primer uso vuelve a aparecer al abrir. */
    data class Failed(val failure: StorageFailure) : CompleteOnboardingResult
}

/**
 * Marca el fin del primer uso, en su propia transacción. `Ahora no` y `Empezar de cero` también
 * terminan el primer uso: si no, la bienvenida volvería en cada apertura. Si ya estaba marcado, no
 * escribe nada.
 */
class CompleteOnboardingUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(): CompleteOnboardingResult = try {
        transactions.write {
            if (settings.get().onboardingCompletedAt != null) {
                TxResult.Rollback(CompleteOnboardingResult.Completed)
            } else {
                settings.markOnboardingCompleted(clock.now())
                TxResult.Commit(CompleteOnboardingResult.Completed)
            }
        }
    } catch (failure: StorageFailureException) {
        CompleteOnboardingResult.Failed(failure.failure)
    }
}
