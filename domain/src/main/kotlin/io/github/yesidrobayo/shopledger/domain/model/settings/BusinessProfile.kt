package io.github.yesidrobayo.shopledger.domain.model.settings

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints

/**
 * Los ajustes que son datos del negocio: cambiarlos cuenta como un cambio sin respaldar.
 *
 * [businessName] encabeza los comprobantes, y falta hasta que la persona lo escribe.
 * [defaultTargetMargin] es el margen objetivo cuando ni el producto ni su categoría fijan uno.
 */
data class BusinessProfile(val businessName: String?, val defaultTargetMargin: BasisPoints)
