package io.github.yesidrobayo.shopledger.domain.model.settings

/**
 * Las preferencias de uso de la app: viajan en la copia con el resto de los ajustes, pero cambiarlas
 * no cuenta como un cambio sin respaldar, porque no son datos del negocio.
 *
 * [lowStockNotificationsEnabled] apaga los avisos de stock bajo, no la lista de productos por
 * agotarse, y no es retroactivo.
 */
data class AppPreferences(
    val themeMode: ThemeMode,
    val lowStockNotificationsEnabled: Boolean,
    val appLockEnabled: Boolean,
    val appLockTimeout: AppLockTimeout,
    val meteredBackupPolicy: MeteredBackupPolicy,
)
