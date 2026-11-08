package io.github.yesidrobayo.shopledger.domain.usecase.payment

import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDraft
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodInputError
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodRepository
import io.github.yesidrobayo.shopledger.domain.rule.payment.PaymentMethodCheck
import io.github.yesidrobayo.shopledger.domain.rule.payment.PaymentMethodRules

/**
 * Revisa [draft] con sus reglas y con lo que solo sabe la base, dentro de la transacción que lo va
 * a guardar. Junta todos los errores, para mostrarlos a la vez.
 *
 * [editing] es el método que se edita, o `null` al crear uno. Además de las reglas, revisa:
 *
 * - que ningún otro vigente tenga el mismo nombre. Uno archivado no reserva su nombre, así que al
 *   editar un archivado no se compara: se compara al reactivarlo;
 * - que una cuenta de dinero nueva exista y esté vigente. La que ya tenía se puede conservar aunque
 *   se haya archivado después, para no impedir que se edite el resto;
 * - que no cambie el tipo de un método con ventas.
 *
 * Lo válido sale con la imagen de cobro que ya tenía el método: se elige aparte.
 */
internal suspend fun checkDraft(
    methods: PaymentMethodRepository,
    draft: PaymentMethodDraft,
    editing: PaymentMethodRecord?,
): PaymentMethodCheck {
    val ruled = PaymentMethodRules.check(draft, isInitial = editing?.systemKey != null)
    val errors = mutableSetOf<PaymentMethodInputError>()
    val proposed = when (ruled) {
        is PaymentMethodCheck.Valid -> ruled.details

        is PaymentMethodCheck.Invalid -> {
            errors += ruled.errors
            null
        }
    }
    // El nombre se compara aunque otro campo falle, para mostrar ese error también.
    val normalizedName = PaymentMethodRules.normalizedNameOf(draft.name)
    if (normalizedName != null && editing?.isArchived != true) {
        val holder = methods.findActiveByNormalizedName(normalizedName)
        if (holder != null && holder.id != editing?.id) errors += PaymentMethodInputError.NAME_TAKEN
    }
    val account = proposed?.moneyAccountId
    if (account != null && account != editing?.details?.moneyAccountId &&
        !methods.isActiveMoneyAccount(account)
    ) {
        errors += PaymentMethodInputError.MONEY_ACCOUNT_UNAVAILABLE
    }
    if (editing != null && draft.kind != editing.details.kind &&
        methods.hasSalePayments(editing.id)
    ) {
        errors += PaymentMethodInputError.KIND_LOCKED
    }
    return if (proposed == null || errors.isNotEmpty()) {
        PaymentMethodCheck.Invalid(errors)
    } else {
        PaymentMethodCheck.Valid(
            proposed.copy(instructionsMediaId = editing?.details?.instructionsMediaId),
        )
    }
}
