package io.github.yesidrobayo.shopledger.domain.rule.customer

import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerDetails
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerForm
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerFormIssue
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CatalogName
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CatalogText

/** Resultado de [CustomerFormRules.check]. */
sealed interface CustomerFormCheck {
    /** Se puede guardar: los datos recortados, con el nombre normalizado y sin textos en blanco. */
    data class Valid(val details: CustomerDetails) : CustomerFormCheck

    /** No se puede guardar, por todos estos motivos a la vez. */
    data class Invalid(val issues: Set<CustomerFormIssue>) : CustomerFormCheck
}

/**
 * Valida un cliente antes de guardarlo, con lo mínimo: solo el nombre es obligatorio, y no existe
 * ningún campo de documento de identidad que validar.
 *
 * Los largos son los de la base. El formato del teléfono y del correo ayuda a digitar, no protege
 * datos: por eso solo se exige al escribir, y una copia restaurada con otro formato no se rechaza.
 * El teléfono acepta dígitos de cualquier sistema de escritura, sin suponer el largo ni el prefijo
 * de ningún país.
 */
object CustomerFormRules {
    /** Largo máximo del nombre. */
    const val NAME_MAX: Int = 80

    /** Largo máximo del teléfono. */
    const val PHONE_MAX: Int = 30

    /** Largo máximo del correo. */
    const val EMAIL_MAX: Int = 120

    /** Largo máximo de las notas. */
    const val NOTES_MAX: Int = 500

    /** Lo que un teléfono admite además de dígitos: espacio, más, guion y paréntesis. */
    private val PHONE_SIGNS = setOf(' ', '+', '-', '(', ')')

    fun check(form: CustomerForm): CustomerFormCheck {
        val issues = mutableSetOf<CustomerFormIssue>()
        val name = CatalogText.name(form.name, NAME_MAX)
        when (name) {
            CatalogName.Empty -> issues += CustomerFormIssue.NAME_EMPTY
            CatalogName.TooLong -> issues += CustomerFormIssue.NAME_TOO_LONG
            is CatalogName.Valid -> Unit
        }
        val phone = optional(form.phone)
        if (phone != null) {
            if (CatalogText.length(phone) > PHONE_MAX) issues += CustomerFormIssue.PHONE_TOO_LONG
            if (!isPhone(phone)) issues += CustomerFormIssue.PHONE_FORMAT
        }
        val email = optional(form.email)
        if (email != null) {
            if (CatalogText.length(email) > EMAIL_MAX) issues += CustomerFormIssue.EMAIL_TOO_LONG
            if (!isEmail(email)) issues += CustomerFormIssue.EMAIL_FORMAT
        }
        val notes = optional(form.notes)
        if (notes != null && CatalogText.length(notes) > NOTES_MAX) {
            issues += CustomerFormIssue.NOTES_TOO_LONG
        }
        return if (name is CatalogName.Valid && issues.isEmpty()) {
            CustomerFormCheck.Valid(
                CustomerDetails(
                    contact = CustomerContact(
                        name = name.name,
                        normalizedName = name.normalizedName,
                        phone = phone,
                        email = email,
                        notes = notes,
                    ),
                    priceListId = form.priceListId,
                ),
            )
        } else {
            CustomerFormCheck.Invalid(issues)
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
