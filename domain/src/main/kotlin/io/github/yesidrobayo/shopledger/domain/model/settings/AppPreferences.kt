package io.github.yesidrobayo.shopledger.domain.model.settings

/**
 * Las preferencias de uso de la app: viajan en la copia con el resto de los ajustes, pero cambiarlas
 * no cuenta como un cambio sin respaldar, porque no son datos del negocio.
 *
 * - [lowStockNotificationsEnabled] apaga los avisos de stock bajo, no la lista de productos por
 *   agotarse, y no es retroactivo.
 * - [colorPalette] solo cambia colores: no depende de [themeMode].
 * - [discreetModeEnabled] oculta los montos en pantalla hasta que la persona los pide.
 * - [mediaOnMetered] deja subir fotos con datos móviles.
 * - [updateCheckEnabled] ofrece buscar una versión nueva. Apagado, la app no hace esa consulta ni
 *   la recuerda.
 */
data class AppPreferences(
    val themeMode: ThemeMode,
    val colorPalette: ColorPalette,
    val lowStockNotificationsEnabled: Boolean,
    val appLockEnabled: Boolean,
    val appLockTimeout: AppLockTimeout,
    val meteredBackupPolicy: MeteredBackupPolicy,
    val discreetModeEnabled: Boolean,
    val mediaOnMetered: Boolean,
    val updateCheckEnabled: Boolean,
)
