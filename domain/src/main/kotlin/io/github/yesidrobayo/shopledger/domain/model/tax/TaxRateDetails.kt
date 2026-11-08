package io.github.yesidrobayo.shopledger.domain.model.tax

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints

/**
 * Lo que la persona escribe de una tasa, ya revisado.
 *
 * [name] va recortado, de 1 a 40 caracteres. [normalizedName] es [name] pasado por
 * `TextNormalizer`: con él se compara si dos nombres son el mismo, y no se repite entre las
 * vigentes. [rate] va de 0 % a 100 %; una tasa de 0 % sirve para productos exentos.
 */
data class TaxRateDetails(val name: String, val normalizedName: String, val rate: BasisPoints)
