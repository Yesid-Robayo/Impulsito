package io.github.yesidrobayo.shopledger.domain.model.backup

/**
 * El espacio del teléfono que importa al respaldo, leído en el momento.
 *
 * @property usableBytes el espacio libre que la app puede usar donde vive la base.
 * @property databaseBytes lo que ocupa la base, con su diario de escritura.
 */
data class DeviceStorage(val usableBytes: Long, val databaseBytes: Long)
