package io.github.yesidrobayo.shopledger.domain.usecase.customer

import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.MessageTemplateRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.customer.ReminderRules
import io.github.yesidrobayo.shopledger.domain.rule.customer.ReminderTemplateIssue
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe

/** Cómo termina guardar la plantilla del recordatorio. Salvo [Saved], no se escribió nada. */
sealed interface SaveReminderTemplateResult {
    data object Saved : SaveReminderTemplateResult

    data class Invalid(val issue: ReminderTemplateIssue) : SaveReminderTemplateResult

    data class StorageFailed(val failure: StorageFailure) : SaveReminderTemplateResult
}

/**
 * La plantilla del recordatorio de pago en un idioma: la que la persona escribió, o `null` para usar
 * el texto de los recursos. Guardarla o restablecerla escribe en una sola transacción, que cuenta
 * como un cambio: la plantilla vive en la base y viaja en las copias.
 */
class ReminderTemplateUseCases(
    private val transactions: TransactionRunner,
    private val templates: MessageTemplateRepository,
    private val clock: Clock,
) {
    suspend fun read(languageTag: String): String? =
        templates.find(ReminderRules.PAYMENT_REMINDER, languageTag)

    /** Guarda [template] como la plantilla de [languageTag], si es válida. */
    suspend fun save(languageTag: String, template: String): SaveReminderTemplateResult {
        ReminderRules.check(template)?.let { return SaveReminderTemplateResult.Invalid(it) }
        return storageSafe(SaveReminderTemplateResult::StorageFailed) {
            transactions.write {
                templates.save(ReminderRules.PAYMENT_REMINDER, languageTag, template, clock.now())
                TxResult.Commit(SaveReminderTemplateResult.Saved)
            }
        }
    }

    /** Vuelve al texto de los recursos en [languageTag]. Devuelve `false` si no pudo escribir. */
    suspend fun reset(languageTag: String): Boolean = storageSafe({ false }) {
        transactions.write {
            templates.reset(ReminderRules.PAYMENT_REMINDER, languageTag)
            TxResult.Commit(true)
        }
    }
}
