package io.github.yesidrobayo.shopledger.domain.model.backup

/**
 * El espacio del servicio WebDAV que vio la última sonda de la cabeza.
 *
 * @property availableBytes el espacio libre que informa el servicio. Sin tope conocido no hay
 *   espacio que guardar.
 * @property copyBytes lo que ocupa una copia, el tamaño de la cabeza en ese momento.
 */
data class ServiceSpace(val availableBytes: Long, val copyBytes: Long)
