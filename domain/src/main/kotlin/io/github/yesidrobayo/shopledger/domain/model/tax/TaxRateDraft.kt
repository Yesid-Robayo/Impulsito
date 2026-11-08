package io.github.yesidrobayo.shopledger.domain.model.tax

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints

/**
 * Lo que la persona escribe en la hoja de una tasa, antes de revisarlo.
 *
 * [name] va tal como se escribió, sin recortar. [rate] ya viene en puntos básicos: la pantalla lee
 * el porcentaje con el formato regional, y aquí solo se revisa su rango. Con [useAsDefault], la
 * tasa queda como la de los productos que no tienen una propia.
 */
data class TaxRateDraft(val name: String, val rate: BasisPoints, val useAsDefault: Boolean)
