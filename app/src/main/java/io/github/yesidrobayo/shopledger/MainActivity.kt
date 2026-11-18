package io.github.yesidrobayo.shopledger

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.OpeningStep
import io.github.yesidrobayo.shopledger.data.RescueCause
import io.github.yesidrobayo.shopledger.data.safety.BootGuard
import io.github.yesidrobayo.shopledger.di.AppContainer
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEventType
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditResult
import io.github.yesidrobayo.shopledger.domain.model.settings.PreferenceChange
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.settings.ChangePreferenceResult
import io.github.yesidrobayo.shopledger.domain.usecase.settings.ChangePreferenceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.settings.ObserveAppLockUseCase
import io.github.yesidrobayo.shopledger.lock.AppLockState
import io.github.yesidrobayo.shopledger.lock.BiometricAuthenticator
import io.github.yesidrobayo.shopledger.lock.LocalDeviceAuthenticator
import io.github.yesidrobayo.shopledger.lock.LocalWindowProtection
import io.github.yesidrobayo.shopledger.lock.WindowProtection
import io.github.yesidrobayo.shopledger.lock.rememberDiscreetModeSwitch
import io.github.yesidrobayo.shopledger.lock.rememberSensitiveCheck
import io.github.yesidrobayo.shopledger.navigation.AppNavHost
import io.github.yesidrobayo.shopledger.navigation.AppOpenRequest
import io.github.yesidrobayo.shopledger.ui.backup.restartForRestore
import io.github.yesidrobayo.shopledger.ui.components.DemoFrame
import io.github.yesidrobayo.shopledger.ui.components.LocalDemoMode
import io.github.yesidrobayo.shopledger.ui.components.LocalDiscreetModeSwitch
import io.github.yesidrobayo.shopledger.ui.format.BusinessDisplay
import io.github.yesidrobayo.shopledger.ui.format.ProvideBusinessFormats
import io.github.yesidrobayo.shopledger.ui.lock.AppLockGate
import io.github.yesidrobayo.shopledger.ui.lock.LockRoute
import io.github.yesidrobayo.shopledger.ui.rescue.OpeningScreen
import io.github.yesidrobayo.shopledger.ui.rescue.OpeningStage
import io.github.yesidrobayo.shopledger.ui.rescue.RescueReason
import io.github.yesidrobayo.shopledger.ui.rescue.RescueRoute
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.Appearance
import io.github.yesidrobayo.shopledger.ui.theme.DefaultColorPalette
import io.github.yesidrobayo.shopledger.ui.theme.LocalPaletteChooser
import io.github.yesidrobayo.shopledger.ui.theme.PaletteChooser
import io.github.yesidrobayo.shopledger.ui.theme.SplashThemes
import io.github.yesidrobayo.shopledger.ui.theme.isDark
import io.github.yesidrobayo.shopledger.update.ApkUpdateFiles
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * La única actividad de la app: aplica la paleta y el modo que eligió la persona, y muestra la
 * navegación.
 *
 * La paleta y el modo viven en la base, que se abre fuera del hilo principal. Hasta leerlos se ve
 * solo el papel de la paleta predeterminada, sin contenido: así no aparece un destello de otros
 * colores. Mientras la compuerta del arranque copia y migra, o termina una restauración, se dice
 * sobre ese papel, sin cifras. Si la compuerta manda al rescate, la pantalla de rescate reemplaza a
 * toda la navegación, con la paleta predeterminada y el tema del teléfono: los guardados viven en
 * la base, que no se lee.
 *
 * También sigue el conteo de arranques fallidos: anota un intento al crearse y lo vacía tras unos
 * segundos estables o al dejar de verse. Si el proceso abrió en modo seguro, tras tres caídas
 * seguidas al abrir, muestra la pantalla de rescate sin abrir la base.
 *
 * Con la paleta llegan el formato regional, la moneda y el modo discreto: toda pantalla con cifras
 * los recibe de aquí, y cambian a la vez cuando la persona los cambia.
 *
 * Es una [AppCompatActivity] porque en Android 8 a 12 el idioma elegido dentro de la app solo se
 * aplica a ese tipo de actividad. Sigue siendo una `FragmentActivity`, así que el desbloqueo con
 * huella o rostro también funciona aquí.
 *
 * Con el bloqueo encendido, la navegación va detrás de su compuerta: bloqueada, no se compone nada del
 * negocio, y la ventana no deja capturas ni muestra datos en Recientes. Un aviso tocado con la app
 * bloqueada espera a que la persona desbloquee, y luego abre su destino. El rescate nunca queda
 * detrás del bloqueo: sin base no hay ajuste que leer, y la persona debe poder salvar sus datos.
 */
class MainActivity : AppCompatActivity() {
    private val shownAppearance = mutableStateOf<Appearance?>(null)
    private val shownDisplay = mutableStateOf<BusinessDisplay?>(null)
    private val rescueReason = mutableStateOf<RescueReason?>(null)
    private val openingStage = mutableStateOf(OpeningStage.OPENING)
    private val onboardingPending = mutableStateOf(false)
    private val openRequest = mutableStateOf<AppOpenRequest?>(null)

    /** Si este proceso muestra la demostración, y si ya se pidió salir de ella. */
    private val demoActive = mutableStateOf(false)
    private val demoExiting = mutableStateOf(false)

    /** Lo que la ventana protege: capturas, superposiciones y toques tapados. */
    private var protection: WindowProtection? = null

    /** Guarda las preferencias de uso, como el bloqueo apagado o el modo discreto. */
    private var lockSettings: ChangePreferenceUseCase? = null

    /** La paleta y el modo que leyó de los ajustes, o `null` mientras la base no responde. */
    internal val appearance: State<Appearance?> get() = shownAppearance

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as ShopLedgerApp
        // Paso 3 del conteo, en la primera línea: hasta quedar estable, una caída cuenta como fallo.
        withDiskPermit { app.bootGuard.onActivityCreated() }
        // El manifiesto arranca con la pantalla de inicio; la app, también el rescate y el modo
        // seguro, se dibuja sobre el papel de siempre.
        setTheme(R.style.Theme_ShopLedger)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Solo en debug: un extra de adb abre el arnés del respaldo encima. En release no hace nada.
        if (savedInstanceState == null) DebugEntry.openIfRequested(this, intent)
        // Un aviso de stock abre su producto. Al recrearse la actividad, el pedido ya se cumplió.
        if (savedInstanceState == null) openRequest.value = AppOpenRequest.readFrom(intent)
        val container = app.container
        // Un APK que quedó de una actualización anterior se borra al abrir la app de cero. No al
        // recrearla: el instalador de Android puede seguir abierto con él. Abrir el archivo desde
        // el instalador no crea esta pantalla, así que nunca se borra mientras lo lee.
        if (savedInstanceState == null) {
            lifecycleScope.launch(container.dispatchers.io) {
                ApkUpdateFiles.clear(applicationContext)
            }
        }
        followStability(app)
        val appLock = container.appLock
        val protection = WindowProtection.of(window).also { this.protection = it }
        // Recientes nunca muestra la pantalla de la app, con cualquier ajuste.
        if (Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {
            setRecentsScreenshotEnabled(false)
        }
        // Con el bloqueo encendido, ni capturas ni Recientes muestran el negocio.
        lifecycleScope.launch {
            appLock.state.collect { state ->
                protection.lockEnabled(
                    when (state) {
                        AppLockState.Unknown -> false
                        is AppLockState.Open -> state.lockEnabled
                        AppLockState.Locked -> true
                    },
                )
            }
        }
        // Lo que hace la compuerta mientras se espera la base: copiar y migrar, o restaurar.
        lifecycleScope.launch {
            container.data.opening.collect { openingStage.value = it.toStage() }
        }
        lifecycleScope.launch {
            // En modo seguro la base no se abre: la pantalla de rescate no la necesita.
            if (app.safeMode) {
                appLock.withoutSettings()
                rescueReason.value = RescueReason.SafeMode
                return@launch
            }
            when (val access = container.data.open()) {
                is DataAccess.Ready -> {
                    // Con datos de ejemplo no sale ningún aviso: la banda lo dice arriba de todo.
                    val demo = container.demoMode.isActive()
                    demoActive.value = demo
                    // Desde aquí se avisa lo que quedó pendiente y cada alerta nueva.
                    if (!demo) {
                        container.lowStockAlertDelivery.follow(access.ports)
                        container.expiryNoticeDelivery.follow(access.ports)
                        container.dailySummaryDelivery.follow(access.ports)
                        container.recurringReminderDelivery.follow(access.ports)
                        container.revenueLimitNoticeDelivery.follow(access.ports)
                        // La búsqueda automática de versión, si está encendida, con KEEP.
                        container.updateCheckDelivery.follow(access.ports)
                        // El widget se redibuja con cada venta y con la paleta o el modo discreto. Lee
                        // siempre la base real, así que en la demostración no sigue la de ejemplo.
                        container.quickSaleWidget.follow(access.ports)
                    }
                    lockSettings = ChangePreferenceUseCase(access.ports.settings, container.clock)
                    launch {
                        ObserveAppLockUseCase(access.ports.settings)().collect {
                            appLock.apply(it.enabled, it.timeout)
                        }
                    }
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        access.ports.settings
                            .observe()
                            .map {
                                Triple(
                                    Appearance(
                                        it.preferences.colorPalette,
                                        it.preferences.themeMode,
                                    ),
                                    BusinessDisplay.of(it),
                                    it.onboardingCompletedAt == null,
                                )
                            }
                            .distinctUntilChanged()
                            .collect { (appearance, display, pending) ->
                                // El primer uso se decide antes de mostrar la navegación.
                                onboardingPending.value = pending
                                // El arranque siguiente sale con la paleta guardada, también tras restaurar.
                                SplashThemes.remember(this@MainActivity, appearance.palette)
                                shownAppearance.value = appearance
                                shownDisplay.value = display
                            }
                    }
                }

                // La causa ya quedó registrada. El rescate no lee la base ni navega.
                is DataAccess.Rescue -> {
                    appLock.withoutSettings()
                    rescueReason.value = access.cause.toReason()
                }
            }
        }
        // Una verificación fallida queda en la auditoría, sin el texto del sistema.
        val authenticator =
            BiometricAuthenticator(this, appLock) {
                record(container, AuditEventType.AUTH_FAILED, AuditResult.FAILURE)
            }
        setContent {
            val appearance = shownAppearance.value
            val darkTheme = appearance.isDark(systemDark = isSystemInDarkTheme())
            // Los iconos de las barras del sistema siguen al modo de la app, no al del teléfono: con
            // el modo fijado al revés del sistema, se perderían sobre el papel.
            DisposableEffect(darkTheme) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            AppTheme(palette = appearance?.palette ?: DefaultColorPalette, darkTheme = darkTheme) {
                val reason = rescueReason.value
                if (reason != null) {
                    // El rescate nunca va detrás del bloqueo, pero restaurar pide la verificación
                    // del sistema si el teléfono tiene bloqueo de pantalla. La protección de la
                    // ventana cubre el código de una copia cifrada mientras se escribe.
                    CompositionLocalProvider(
                        LocalDeviceAuthenticator provides authenticator,
                        LocalWindowProtection provides protection,
                    ) {
                        RescueRoute(reason)
                    }
                } else if (appearance == null) {
                    OpeningScreen(openingStage.value)
                } else {
                    val lockState by appLock.state.collectAsStateWithLifecycle()
                    CompositionLocalProvider(
                        LocalDeviceAuthenticator provides authenticator,
                        LocalWindowProtection provides protection,
                    ) {
                        // Ocultar las cifras es inmediato; mostrarlas con el bloqueo pide verificar.
                        val discreetSwitch =
                            rememberDiscreetModeSwitch(rememberSensitiveCheck(), ::savePreference)
                        // Elegir colores cambia la paleta en pantalla al instante, y la guarda después.
                        val paletteChooser =
                            remember {
                                PaletteChooser(
                                    shown = { shownAppearance.value },
                                    show = { shownAppearance.value = it },
                                    save = ::savePreference,
                                )
                            }
                        CompositionLocalProvider(
                            LocalDiscreetModeSwitch provides discreetSwitch,
                            LocalPaletteChooser provides paletteChooser,
                        ) {
                            ProvideBusinessFormats(shownDisplay.value) {
                                AppLockGate(
                                    state = lockState,
                                    locked = {
                                        LockRoute(appLock, onNoDeviceCredential = {
                                            turnLockOffWithoutCredential(container)
                                        })
                                    },
                                ) {
                                    // Con datos de ejemplo, la banda fija va encima de toda
                                    // pantalla, y lo que depende de la nube se desactiva.
                                    DemoFrame(
                                        active = demoActive.value,
                                        onExit = { exitDemo(container) },
                                        exiting = demoExiting.value,
                                    ) {
                                        CompositionLocalProvider(
                                            LocalDemoMode provides demoActive.value,
                                        ) {
                                            // Un aviso es del negocio real: con datos de ejemplo
                                            // no abre nada, porque el número sería de otra base.
                                            AppNavHost(
                                                onboardingPending = onboardingPending.value,
                                                openRequest =
                                                    openRequest.value.takeUnless {
                                                        demoActive.value
                                                    },
                                                onOpenHandled = { openRequest.value = null },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * `Salir` de la banda: borra la marca de la demostración y reinicia la app, que abre la base del
     * negocio tal como estaba. Se pide una sola vez; si la marca no se pudo borrar, se puede tocar
     * otra vez.
     */
    private fun exitDemo(container: AppContainer) {
        if (demoExiting.value) return
        demoExiting.value = true
        lifecycleScope.launch {
            if (container.demoMode.stop()) {
                restartForRestore(this@MainActivity)
            } else {
                demoExiting.value = false
            }
        }
    }

    /**
     * El teléfono perdió su bloqueo de pantalla con el bloqueo de la app encendido: la app se abre y
     * el bloqueo se apaga, porque nunca deja a nadie fuera de sus datos. Si el ajuste no se guarda,
     * igual se abre, y la próxima vez se vuelve a apagar.
     */
    private fun turnLockOffWithoutCredential(container: AppContainer) {
        container.appLock.noDeviceCredential()
        val change = lockSettings ?: return
        container.applicationScope.launch {
            val saved = change(PreferenceChange.AppLock(false)) == ChangePreferenceResult.Saved
            val result = if (saved) AuditResult.SUCCESS else AuditResult.FAILURE
            container.data.auditRecorder.record(AuditEventType.APP_LOCK_CHANGED, result)
        }
    }

    /** Anota [type] en la auditoría, aparte: una falla al anotar nunca detiene nada. */
    private fun record(container: AppContainer, type: AuditEventType, result: AuditResult) {
        container.applicationScope.launch { container.data.auditRecorder.record(type, result) }
    }

    /**
     * En una pantalla entera sensible, como la de la contraseña del servicio, un gesto que llega a
     * través de otra app dibujada encima se descarta completo, y la pantalla avisa.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean =
        if (protection?.rejects(ev) == true) true else super.dispatchTouchEvent(ev)

    /** Guarda una preferencia de uso. Sin base abierta no hay dónde, y queda como estaba. */
    private suspend fun savePreference(change: PreferenceChange): ChangePreferenceResult =
        lockSettings?.invoke(change) ?: ChangePreferenceResult.Failed(StorageFailure.IO_ERROR)

    /**
     * Paso 4 del conteo: tras unos segundos con la pantalla abierta, o al dejar de verse, el arranque
     * cuenta como bueno y el conteo vuelve a cero. Abrir y cerrar deprisa no es un fallo. En modo
     * seguro no cambia nada: salir del rescate no prueba que la app abra.
     */
    private fun followStability(app: ShopLedgerApp) {
        lifecycleScope.launch {
            delay(BootGuard.STABLE_AFTER_MILLIS)
            withDiskPermit { app.bootGuard.onStable() }
        }
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) withDiskPermit { app.bootGuard.onStable() }
            },
        )
    }

    /** Con la app abierta, tocar un aviso de stock llega aquí. Lo desconocido no cambia nada. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AppOpenRequest.readFrom(intent)?.let { openRequest.value = it }
    }
}

/** La causa del rescate en los términos de la pantalla, que no ve la capa de datos. */
private fun RescueCause.toReason(): RescueReason = when (this) {
    RescueCause.DatabaseMissing -> RescueReason.DatabaseMissing
    RescueCause.NewerVersion -> RescueReason.NewerVersion
    RescueCause.OpenFailed -> RescueReason.OpenFailed
    RescueCause.MigrationFailed -> RescueReason.MigrationFailed
    RescueCause.PreMigrationCopyFailed -> RescueReason.PreMigrationCopyFailed
    is RescueCause.NotEnoughSpace -> RescueReason.NotEnoughSpace(missingBytes)
    RescueCause.RestoreJournalUnreadable -> RescueReason.RestoreJournalUnreadable
    RescueCause.RestoreLeftNoDatabase -> RescueReason.RestoreLeftNoDatabase
}

/** Lo que hace la compuerta, en los términos de la pantalla. */
private fun OpeningStep.toStage(): OpeningStage = when (this) {
    OpeningStep.OPENING -> OpeningStage.OPENING
    OpeningStep.MIGRATING -> OpeningStage.MIGRATING
    OpeningStep.RESTORING -> OpeningStage.RESTORING
}
