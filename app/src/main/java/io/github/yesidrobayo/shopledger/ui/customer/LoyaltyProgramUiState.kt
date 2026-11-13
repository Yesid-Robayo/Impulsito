package io.github.yesidrobayo.shopledger.ui.customer

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.PointsSummary

/** Lo que muestra la pantalla del programa de puntos. */
sealed interface LoyaltyProgramUiState {
    /** Leyendo el programa. Los datos son locales: casi nunca se ve. */
    data object Loading : LoyaltyProgramUiState

    /** La lectura falló: se dice, con `Reintentar`. Nada de lo guardado cambió. */
    data object ReadFailed : LoyaltyProgramUiState

    /**
     * Con datos. [form] es lo que la persona tiene escrito; [savedEnabled], si el programa guardado
     * está activo. Con los dos apagados solo se ve el interruptor y la invitación a activarlo.
     */
    data class Ready(
        val form: LoyaltyForm,
        val savedEnabled: Boolean,
        val valuesChanged: Boolean,
        val example: LoyaltyExample?,
        val points: PointsSummary,
        val hasCustomers: Boolean,
        val saving: Boolean = false,
        val saveFailed: Boolean = false,
        val outOfSpace: Boolean = false,
        val confirmTurnOff: Boolean = false,
        val savedMessage: Boolean = false,
    ) : LoyaltyProgramUiState
}

/**
 * Lo escrito en el formulario. Los errores se muestran solo después de intentar guardar, y cada uno
 * se borra al cambiar su campo.
 */
data class LoyaltyForm(
    val enabled: Boolean,
    val amountPerPoint: String,
    val pointValue: String,
    val amountPerPointError: LoyaltyAmountError? = null,
    val pointValueError: LoyaltyAmountError? = null,
)

/** Qué falla en uno de los dos montos. */
enum class LoyaltyAmountError {
    /** Vacío o en cero con el programa activo. */
    REQUIRED,

    /** No es un número del formato regional. */
    NOT_A_NUMBER,

    /** Tiene más decimales que la moneda. */
    DECIMALS,

    /** Pasa del tope de un precio. */
    TOO_HIGH,
}

/**
 * El ejemplo en vivo: una venta de [saleAmount] gana [earnedPoints], y [redeemedPoints] descuentan
 * [discount]. Solo existe si los dos montos se leen bien.
 */
data class LoyaltyExample(
    val saleAmount: Money,
    val earnedPoints: Long,
    val redeemedPoints: Long,
    val discount: Money,
)

/** Lo que la persona hace en la pantalla del programa de puntos. */
sealed interface LoyaltyProgramAction {
    data object Back : LoyaltyProgramAction

    data object Retry : LoyaltyProgramAction

    /** Tocó el interruptor. Apagar un programa guardado activo pide confirmar. */
    data class SetEnabled(val enabled: Boolean) : LoyaltyProgramAction

    data class AmountPerPoint(val text: String) : LoyaltyProgramAction

    data class PointValue(val text: String) : LoyaltyProgramAction

    data object Save : LoyaltyProgramAction

    data object ConfirmTurnOff : LoyaltyProgramAction

    data object DismissTurnOff : LoyaltyProgramAction

    /** Abre la lista de clientes, desde el aviso de que no hay ninguno. */
    data object OpenCustomers : LoyaltyProgramAction

    data object MessageShown : LoyaltyProgramAction
}
