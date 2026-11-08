package io.github.yesidrobayo.shopledger.domain.usecase.tax

import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateDraft
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateInputError
import io.github.yesidrobayo.shopledger.domain.port.TaxRateRepository
import io.github.yesidrobayo.shopledger.domain.port.TaxSettingsRepository
import io.github.yesidrobayo.shopledger.domain.rule.tax.TaxRateCheck
import io.github.yesidrobayo.shopledger.domain.rule.tax.TaxRateRules
import kotlin.time.Instant

/**
 * Revisa [draft]: sus rangos y, si están bien, que ninguna otra tasa vigente tenga el mismo
 * nombre. [editingId] es la tasa que se edita, que no choca consigo misma.
 */
internal suspend fun checkDraft(
    rates: TaxRateRepository,
    draft: TaxRateDraft,
    editingId: Long?,
): TaxRateCheck = when (val checked = TaxRateRules.check(draft)) {
    is TaxRateCheck.Invalid -> checked

    is TaxRateCheck.Valid -> {
        val sameName = rates.findActiveByNormalizedName(checked.details.normalizedName)
        if (sameName != null && sameName.id != editingId) {
            TaxRateCheck.Invalid(setOf(TaxRateInputError.NAME_TAKEN))
        } else {
            checked
        }
    }
}

/**
 * Deja como tasa por defecto [defaultTaxRateId], o ninguna con `null`. No escribe si ya lo era: así
 * no se cuenta un cambio de más.
 */
internal suspend fun TaxSettingsRepository.setDefault(
    current: TaxSettings,
    defaultTaxRateId: Long?,
    at: Instant,
) {
    if (current.defaultTaxRateId != defaultTaxRateId) {
        update(current.copy(defaultTaxRateId = defaultTaxRateId), at)
    }
}
