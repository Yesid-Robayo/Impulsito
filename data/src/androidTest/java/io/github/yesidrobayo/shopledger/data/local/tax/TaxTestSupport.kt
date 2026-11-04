package io.github.yesidrobayo.shopledger.data.local.tax

import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer

// Apoyo de las pruebas de impuestos. Los nombres y las tasas son inventados: ninguno es el de un
// país.

/** Una tasa que escribió la persona, vigente salvo que se diga otra cosa. */
internal fun taxRate(name: String, rateBp: Long, archivedAt: Long? = null): TaxRateEntity =
    TaxRateEntity(
        name = name,
        normalizedName = TextNormalizer.normalize(name),
        rateBp = rateBp,
        archivedAt = archivedAt,
        createdAt = CREATED_AT,
        updatedAt = CREATED_AT,
    )
