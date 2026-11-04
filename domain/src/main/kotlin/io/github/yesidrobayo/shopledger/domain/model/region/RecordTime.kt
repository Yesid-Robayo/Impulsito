package io.github.yesidrobayo.shopledger.domain.model.region

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import kotlin.time.Instant

/**
 * Cuándo ocurrió un registro, en las dos formas que se guardan juntas: el instante, igual en
 * cualquier lugar del mundo, y su fecha de negocio, calculada una sola vez con la zona del negocio
 * vigente al escribir.
 *
 * Van juntas porque la fecha no se puede volver a deducir del instante: si después la persona
 * cambia la zona, el mismo instante daría otro día, y el registro debe conservar el suyo.
 */
data class RecordTime(val occurredAt: Instant, val businessDate: BusinessDate)
