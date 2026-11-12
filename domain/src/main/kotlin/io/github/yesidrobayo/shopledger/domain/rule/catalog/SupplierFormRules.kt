package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierFormIssue

/** Resultado de [SupplierFormRules.check]. */
sealed interface SupplierFormCheck {
    /** Se puede guardar: los datos recortados, con el nombre normalizado y sin textos en blanco. */
    data class Valid(val details: SupplierDetails) : SupplierFormCheck

    /** No se puede guardar, por todos estos motivos a la vez. */
    data class Invalid(val issues: Set<SupplierFormIssue>) : SupplierFormCheck
}

/**
 * Valida un proveedor antes de guardarlo.
 *
 * El formato del teléfono y del correo ayuda a digitar, no protege datos: por eso solo se exige al
 * escribir, y una copia restaurada con otro formato no se rechaza. El teléfono acepta dígitos de
 * cualquier sistema de escritura, sin suponer el largo ni el prefijo de ningún país.
 */
object SupplierFormRules {
    /** Largo máximo del nombre. */
    const val NAME_MAX: Int = 80

    /** Largo máximo del teléfono. */
    const val PHONE_MAX: Int = 30

    /** Largo máximo del correo. */
    const val EMAIL_MAX: Int = 120

    /** Largo máximo de las notas. */
    const val NOTES_MAX: Int = 500

    /** Plazo de pago máximo, en días: unos diez años. */
    const val PAYMENT_TERMS_MAX_DAYS: Int = 3_650

    /** Lo que un teléfono admite además de dígitos: espacio, más, guion y paréntesis. */
    private val PHONE_SIGNS = setOf(' ', '+', '-', '(', ')')

    fun check(form: SupplierForm): SupplierFormCheck {
        val issues = mutableSetOf<SupplierFormIssue>()
        val name = CatalogText.name(form.name, NAME_MAX)
        when (name) {
            CatalogName.Empty -> issues += SupplierFormIssue.NAME_EMPTY
            CatalogName.TooLong -> issues += SupplierFormIssue.NAME_TOO_LONG
            is CatalogName.Valid -> Unit
        }
        val phone = optional(form.phone)
        if (phone != null) {
            if (CatalogText.length(phone) > PHONE_MAX) issues += SupplierFormIssue.PHONE_TOO_LONG
            if (!isPhone(phone)) issues += SupplierFormIssue.PHONE_FORMAT
        }
        val email = optional(form.email)
        if (email != null) {
            if (CatalogText.length(email) > EMAIL_MAX) issues += SupplierFormIssue.EMAIL_TOO_LONG
            if (!isEmail(email)) issues += SupplierFormIssue.EMAIL_FORMAT
        }
        val notes = optional(form.notes)
        if (notes != null && CatalogText.length(notes) > NOTES_MAX) {
            issues += SupplierFormIssue.NOTES_TOO_LONG
        }
        val days = form.paymentTermsDays
        if (days != null && days !in 0..PAYMENT_TERMS_MAX_DAYS) {
            issues += SupplierFormIssue.PAYMENT_TERMS_OUT_OF_RANGE
        }
        return if (name is CatalogName.Valid && issues.isEmpty()) {
            SupplierFormCheck.Valid(
                SupplierDetails(
                    name = name.name,
                    normalizedName = name.normalizedName,
                    phone = phone,
                    email = email,
                    notes = notes,
                    paymentTermsDays = days,
                ),
            )
        } else {
            SupplierFormCheck.Invalid(issues)
        }
    }

    /** [raw] recortado, o `null` si queda en blanco: un dato opcional vacío no se guarda. */
    private fun optional(raw: String): String? = raw.trim().ifEmpty { null }

    /** Al menos un dígito, y fuera de ellos solo espacios y los signos de marcar. */
    private fun isPhone(text: String): Boolean =
        text.any { it.isDigit() } && text.all { it.isDigit() || it in PHONE_SIGNS }

    /** Una sola arroba, con texto que no sea solo espacios a cada lado. */
    private fun isEmail(text: String): Boolean {
        val parts = text.split('@')
        return parts.size == 2 && parts.all { it.isNotBlank() }
    }
}
