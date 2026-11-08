package io.github.yesidrobayo.shopledger.domain.usecase.payment

import io.github.yesidrobayo.shopledger.domain.model.catalog.MediaKind
import io.github.yesidrobayo.shopledger.domain.model.payment.InstructionsImageChange
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDraft
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodInputError
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.port.MediaAssetRepository
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodRepository
import io.github.yesidrobayo.shopledger.domain.rule.payment.PaymentMethodCheck
import io.github.yesidrobayo.shopledger.domain.rule.payment.PaymentMethodRules
import kotlin.time.Instant

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

/**
 * El id de la imagen de cobro que queda tras [change], anotándola si es nueva. [current] es la que
 * tenía el método. Una imagen que ya estaba anotada, quizá marcada como sin uso, vuelve a usarse:
 * dos imágenes iguales son una sola fila.
 */
internal suspend fun imageIdAfter(
    media: MediaAssetRepository,
    change: InstructionsImageChange,
    current: Long?,
    at: Instant,
): Long? = when (change) {
    InstructionsImageChange.Keep -> current

    InstructionsImageChange.Remove -> null

    is InstructionsImageChange.Use -> {
        val existing = media.findIdBySha256(change.image.sha256)
        if (existing == null) {
            media.insert(change.image, MediaKind.PAYMENT_QR, at)
        } else {
            media.setOrphanedAt(existing, orphanedAt = null)
            existing
        }
    }
}

/**
 * Si la imagen [previous] dejó de usarse, la marca desde [at] para que la limpieza la borre más
 * adelante. Se llama después de escribir el método, para que su referencia ya no cuente.
 */
internal suspend fun releaseImage(media: MediaAssetRepository, previous: Long?, at: Instant) {
    if (previous != null && !media.isReferenced(previous)) media.setOrphanedAt(previous, at)
}
