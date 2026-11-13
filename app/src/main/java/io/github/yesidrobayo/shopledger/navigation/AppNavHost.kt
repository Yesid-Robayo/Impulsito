package io.github.yesidrobayo.shopledger.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.usecase.safety.PolicyConsentUseCase
import io.github.yesidrobayo.shopledger.ui.backup.BackupRoute
import io.github.yesidrobayo.shopledger.ui.cash.CashRegisterRoute
import io.github.yesidrobayo.shopledger.ui.category.CategoriesRoute
import io.github.yesidrobayo.shopledger.ui.components.AppBottomBar
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.BottomBarItem
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.customer.CustomerDetailRoute
import io.github.yesidrobayo.shopledger.ui.customer.CustomerListRoute
import io.github.yesidrobayo.shopledger.ui.customer.LoyaltyProgramRoute
import io.github.yesidrobayo.shopledger.ui.destination.DestinationChoiceRoute
import io.github.yesidrobayo.shopledger.ui.destination.SlotSetupRoute
import io.github.yesidrobayo.shopledger.ui.encryption.EncryptionRoute
import io.github.yesidrobayo.shopledger.ui.expense.ExpensesRoute
import io.github.yesidrobayo.shopledger.ui.experience.BusinessHubRoute
import io.github.yesidrobayo.shopledger.ui.home.HomeRoute
import io.github.yesidrobayo.shopledger.ui.i18n.LanguageSettingsRoute
import io.github.yesidrobayo.shopledger.ui.localcopies.LocalCopiesRoute
import io.github.yesidrobayo.shopledger.ui.money.MoneyAccountsRoute
import io.github.yesidrobayo.shopledger.ui.onboarding.OnboardingRoute
import io.github.yesidrobayo.shopledger.ui.payable.PayableDetailRoute
import io.github.yesidrobayo.shopledger.ui.payable.PayablesRoute
import io.github.yesidrobayo.shopledger.ui.payment.PaymentMethodFormRoute
import io.github.yesidrobayo.shopledger.ui.payment.PaymentMethodsRoute
import io.github.yesidrobayo.shopledger.ui.permission.LocalNotificationPermission
import io.github.yesidrobayo.shopledger.ui.permission.NotificationPermissionHost
import io.github.yesidrobayo.shopledger.ui.permission.NotificationPermissionPrompt
import io.github.yesidrobayo.shopledger.ui.placeholder.EmptyContent
import io.github.yesidrobayo.shopledger.ui.placeholder.PlaceholderAction
import io.github.yesidrobayo.shopledger.ui.placeholder.PlaceholderContent
import io.github.yesidrobayo.shopledger.ui.placeholder.PlaceholderScreen
import io.github.yesidrobayo.shopledger.ui.placeholder.PlaceholderUiState
import io.github.yesidrobayo.shopledger.ui.pricing.PriceListsRoute
import io.github.yesidrobayo.shopledger.ui.product.BulkPriceRoute
import io.github.yesidrobayo.shopledger.ui.product.ProductDetailRoute
import io.github.yesidrobayo.shopledger.ui.product.ProductEditRoute
import io.github.yesidrobayo.shopledger.ui.product.ProductsRoute
import io.github.yesidrobayo.shopledger.ui.purchase.NewPurchaseRoute
import io.github.yesidrobayo.shopledger.ui.purchase.PurchaseDetailRoute
import io.github.yesidrobayo.shopledger.ui.purchase.PurchaseHistoryRoute
import io.github.yesidrobayo.shopledger.ui.reference.ReferenceCurrencyRoute
import io.github.yesidrobayo.shopledger.ui.region.RegionalSettingsRoute
import io.github.yesidrobayo.shopledger.ui.returns.SaleReturnRoute
import io.github.yesidrobayo.shopledger.ui.safety.LocalPolicyPrompt
import io.github.yesidrobayo.shopledger.ui.safety.PolicyGated
import io.github.yesidrobayo.shopledger.ui.safety.PolicyPrompt
import io.github.yesidrobayo.shopledger.ui.safety.PolicyPromptHost
import io.github.yesidrobayo.shopledger.ui.sale.CheckoutRoute
import io.github.yesidrobayo.shopledger.ui.sale.HeldSalesRoute
import io.github.yesidrobayo.shopledger.ui.sale.NewSaleRoute
import io.github.yesidrobayo.shopledger.ui.sale.SaleDetailRoute
import io.github.yesidrobayo.shopledger.ui.sale.SaleHistoryRoute
import io.github.yesidrobayo.shopledger.ui.settings.SettingsEntryRoute
import io.github.yesidrobayo.shopledger.ui.settings.TaxSettingsRoute
import io.github.yesidrobayo.shopledger.ui.stats.StatisticsRoute
import io.github.yesidrobayo.shopledger.ui.stock.InventoryRoute
import io.github.yesidrobayo.shopledger.ui.stock.StockAdjustmentRoute
import io.github.yesidrobayo.shopledger.ui.supplier.SupplierDetailRoute
import io.github.yesidrobayo.shopledger.ui.supplier.SupplierListRoute
import io.github.yesidrobayo.shopledger.ui.theme.AppMotion
import io.github.yesidrobayo.shopledger.ui.unit.UnitsRoute
import io.github.yesidrobayo.shopledger.ui.webdav.WebDavConnectRoute
import kotlinx.coroutines.flow.first

/**
 * La navegación de la app: la pila de Navigation 3 y la barra inferior encima de las cuatro raíces.
 *
 * Cambiar de pestaña es un fundido corto, sin deslizamiento lateral. Abrir un detalle entra desde
 * la derecha con un fundido, y volver lo saca hacia la derecha, también con el gesto predictivo del
 * sistema. Atrás en Inicio no se intercepta: lo resuelve el sistema, que cierra la app.
 *
 * @param openRequest un destino pedido desde fuera, como tocar un aviso de stock: se abre encima de
 *   lo visible, y [onOpenHandled] lo da por cumplido. Durante el primer uso no se abre nada.
 */
@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    onboardingPending: Boolean = false,
    openRequest: AppOpenRequest? = null,
    onOpenHandled: () -> Unit = {},
) {
    val backStack = rememberNavBackStack(AppNavKey.Home)
    val navigator = remember(backStack) { AppNavigator(backStack) }
    // El primer uso se abre encima de Inicio una sola vez, y se cierra solo al terminar.
    LaunchedEffect(onboardingPending) {
        if (onboardingPending && backStack.none { it == AppNavKey.Onboarding }) {
            navigator.open(AppNavKey.Onboarding)
        }
    }
    LaunchedEffect(openRequest, onboardingPending) {
        if (openRequest == null) return@LaunchedEffect
        if (!onboardingPending) navigator.open(openRequest.key)
        onOpenHandled()
    }
    val density = LocalDensity.current
    val messageScope = rememberCoroutineScope()
    val messages = remember(messageScope) { AppMessages(messageScope) }
    val container = (LocalContext.current.applicationContext as ShopLedgerApp).container
    val policy =
        remember(messageScope) {
            PolicyPrompt(PolicyConsentUseCase(container.data.policyConsent), messageScope)
        }
    val context = LocalContext.current
    val notificationPermission =
        remember(messageScope) {
            NotificationPermissionPrompt(
                needsAsking = { NotificationPermissionPrompt.needsAsking(context) },
                isFirstProduct = { hasOnlyOneProduct(container.data.open()) },
                scope = messageScope,
            )
        }
    // Tras restaurar una copia cifrada, su código quedó guardado aquí: se dice una sola vez.
    val codeSaved = stringResource(R.string.recovery_restore_saved)
    LaunchedEffect(Unit) {
        if (container.data.takeRestoredCodeSaved()) {
            messages.show(AppSnackbarVisuals(codeSaved, SnackbarVariant.SUCCESS))
        }
    }
    // Cada apertura vuelve a mirar si cambió lo aceptado, hasta que la persona acepte.
    LifecycleStartEffect(policy) {
        policy.checkOnOpen()
        onStopOrDispose {}
    }
    NavigationMessages(messages.host, navigator.showsBottomBar, modifier) {
        CompositionLocalProvider(
            LocalAppMessages provides messages,
            LocalPolicyPrompt provides policy,
            LocalNotificationPermission provides notificationPermission,
        ) {
            NavDisplay(
                backStack = backStack,
                onBack = { navigator.back() },
                entryDecorators =
                    listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                transitionSpec = { forward(density) },
                popTransitionSpec = { backward(density) },
                predictivePopTransitionSpec = { backward(density) },
                entryProvider = { key -> entryOf(key, navigator) },
            )
            PolicyPromptHost(policy)
            NotificationPermissionHost(notificationPermission)
        }
        AnimatedVisibility(
            visible = navigator.showsBottomBar,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(AppMotion.SHORT, easing = AppMotion.EnterEasing)),
            exit = fadeOut(tween(AppMotion.SHORT / 2, easing = AppMotion.ExitEasing)),
        ) {
            val tabs = TopLevelDestination.entries
            AppBottomBar(
                items = tabs.map {
                    BottomBarItem(stringResource(it.label), it.icon, it.selectedIcon)
                },
                selected = tabs.indexOf(navigator.currentTab),
                onSelect = { navigator.selectTab(tabs[it]) },
                sellLabel = stringResource(R.string.nav_sell),
                onSell = navigator::sell,
            )
        }
    }
}

// Las raíces llevan esta marca: entre dos raíces, el cambio es de pestaña y no de profundidad.
private const val TAB_ROOT = "tabRoot"

private fun entryOf(key: NavKey, navigator: AppNavigator): NavEntry<NavKey> {
    val appKey = requireNotNull(key as? AppNavKey) { "La pila solo guarda claves de la app" }
    val isRoot = TopLevelDestination.ofRoot(appKey) != null
    return NavEntry(key = key, metadata = if (isRoot) mapOf(TAB_ROOT to true) else emptyMap()) {
        ScreenRoute(appKey, navigator)
    }
}

/**
 * La pantalla de [key]: la suya, si ya existe, o su barra y su estado vacío mientras tanto.
 *
 * Cada pantalla nueva agrega aquí su línea, `AppNavKey.X -> XRoute(...)`, sin tocar las de las
 * demás. Su Route obtiene el ViewModel con [rememberAppViewModel] y, si la base no abre, muestra
 * [PlaceholderRoute]. El caso general existe solo mientras queden pantallas por construir.
 */
@Composable
private fun ScreenRoute(key: AppNavKey, navigator: AppNavigator) {
    when (key) {
        AppNavKey.Home -> HomeRoute(navigator)

        AppNavKey.Statistics -> StatisticsRoute(navigator)

        AppNavKey.Onboarding -> OnboardingRoute(navigator)

        AppNavKey.Backup -> BackupRoute(navigator)

        AppNavKey.LocalCopies -> LocalCopiesRoute(navigator)

        AppNavKey.BackupEncryption -> EncryptionRoute(navigator)

        // Toda entrada que conecta un destino pasa antes por la política y los términos.
        is AppNavKey.BackupDestinationChoice ->
            PolicyGated(onDeclined = {
                navigator.back()
            }) { DestinationChoiceRoute(key, navigator) }

        is AppNavKey.WebDavConnect ->
            PolicyGated(onDeclined = { navigator.back() }) { WebDavConnectRoute(key, navigator) }

        AppNavKey.DocumentSlotsSetup ->
            PolicyGated(onDeclined = { navigator.back() }) { SlotSetupRoute(navigator) }

        AppNavKey.ProductList -> ProductsRoute(navigator)

        AppNavKey.Suppliers ->
            SupplierListRoute(navigator, navigation = TopBarNavigation.BACK)

        is AppNavKey.SupplierDetail -> SupplierDetailRoute(key, navigator)

        is AppNavKey.NewSale -> NewSaleRoute(key, navigator)

        is AppNavKey.Purchase -> NewPurchaseRoute(key, navigator)

        is AppNavKey.PurchaseHistory -> PurchaseHistoryRoute(key, navigator)

        is AppNavKey.PurchaseDetail -> PurchaseDetailRoute(key, navigator)

        is AppNavKey.Expenses -> ExpensesRoute(key, navigator)

        AppNavKey.Categories -> CategoriesRoute(key, navigator)

        is AppNavKey.SaleDetail -> SaleDetailRoute(key, navigator)

        is AppNavKey.SaleReturn -> SaleReturnRoute(key, navigator)

        AppNavKey.Checkout -> CheckoutRoute(navigator)

        AppNavKey.SaleHistory -> SaleHistoryRoute(navigator)

        is AppNavKey.ProductEdit -> ProductEditRoute(key, navigator)

        is AppNavKey.ProductDetail -> ProductDetailRoute(key, navigator)

        is AppNavKey.BulkPriceUpdate -> BulkPriceRoute(key, navigator)

        AppNavKey.LanguageSettings ->
            LanguageSettingsRoute(
                onBack = { navigator.back() },
                onOpenFormat = { navigator.open(AppNavKey.RegionalSettings()) },
            )

        AppNavKey.BusinessHub ->
            BusinessHubRoute(onOpen = { navigator.open(it) }, onBack = { navigator.back() })

        AppNavKey.TaxSettings ->
            TaxSettingsRoute(onBack = { navigator.back() }) { PlaceholderRoute(key, navigator) }

        AppNavKey.PaymentMethods -> PaymentMethodsRoute(navigator)

        AppNavKey.PriceLists -> PriceListsRoute(navigator)

        AppNavKey.Units -> UnitsRoute(navigator)

        AppNavKey.LoyaltyProgram -> LoyaltyProgramRoute(key, navigator)

        is AppNavKey.Customers -> CustomerListRoute(key, navigator)

        is AppNavKey.CustomerDetail -> CustomerDetailRoute(key, navigator)

        is AppNavKey.MoneyAccounts -> MoneyAccountsRoute(key, navigator)

        AppNavKey.Payables -> PayablesRoute(AppNavKey.Payables, navigator)

        is AppNavKey.PayableDetail -> PayableDetailRoute(key, navigator)

        AppNavKey.CashRegister -> CashRegisterRoute(navigator)

        AppNavKey.ReferenceCurrency -> ReferenceCurrencyRoute(navigator)

        AppNavKey.HeldSales -> HeldSalesRoute(navigator)

        is AppNavKey.StockAdjustment -> StockAdjustmentRoute(key, navigator)

        is AppNavKey.Inventory -> InventoryRoute(key.filter, navigator)

        is AppNavKey.PaymentMethodEdit -> PaymentMethodFormRoute(key, navigator)

        // Ajustes: solo configuración, en grupos.
        AppNavKey.Settings ->
            SettingsEntryRoute(onOpen = { navigator.open(it) }, onBack = { navigator.back() })

        is AppNavKey.RegionalSettings ->
            RegionalSettingsRoute(
                firstRun = key.firstRun,
                onBack = { navigator.back() },
                // El primer uso sigue con su paso siguiente; hasta que exista, vuelve.
                onConfirmed = { navigator.back() },
            )

        else -> PlaceholderRoute(key, navigator)
    }
}

private val Scene<NavKey>.isTabRoot: Boolean get() = metadata[TAB_ROOT] == true

// Cuánto se desplaza un detalle al entrar o salir: lo justo para que se lea como profundidad.
private val DetailShift = 24.dp

private fun AnimatedContentTransitionScope<Scene<NavKey>>.forward(
    density: Density,
): ContentTransform = if (initialState.isTabRoot && targetState.isTabRoot) {
    tabSwitch()
} else {
    val shift = with(density) { DetailShift.roundToPx() }
    (
        slideInHorizontally(tween(AppMotion.LONG, easing = AppMotion.EnterEasing)) { shift } +
            fadeIn(tween(AppMotion.LONG, easing = AppMotion.EnterEasing))
        ) togetherWith fadeOut(tween(AppMotion.LONG / 2, easing = AppMotion.ExitEasing))
}

private fun AnimatedContentTransitionScope<Scene<NavKey>>.backward(
    density: Density,
): ContentTransform = if (initialState.isTabRoot && targetState.isTabRoot) {
    tabSwitch()
} else {
    val shift = with(density) { DetailShift.roundToPx() }
    fadeIn(tween(AppMotion.LONG, easing = AppMotion.EnterEasing)) togetherWith
        (
            slideOutHorizontally(tween(AppMotion.LONG / 2, easing = AppMotion.ExitEasing)) {
                shift
            } +
                fadeOut(tween(AppMotion.LONG / 2, easing = AppMotion.ExitEasing))
            )
}

private fun tabSwitch(): ContentTransform =
    fadeIn(tween(AppMotion.SHORT, easing = AppMotion.StandardEasing)) togetherWith
        fadeOut(tween(AppMotion.SHORT / 2, easing = AppMotion.ExitEasing))

/**
 * La pantalla de [key] mientras no tenga contenido propio: su barra, su estado vacío y sus accesos.
 * Las raíces muestran su estado vacío real, con la acción que lleva a empezar.
 */
@Composable
internal fun PlaceholderRoute(key: AppNavKey, navigator: AppNavigator) {
    val spec = screenSpecOf(key)
    val title = stringResource(spec.title)
    val navigationLabel =
        when (spec.navigation) {
            TopBarNavigation.NONE -> null
            TopBarNavigation.BACK -> stringResource(R.string.a11y_back)
            TopBarNavigation.CLOSE -> stringResource(R.string.a11y_close)
        }
    val (empty, emptyTarget) = emptyOf(key, title)
    val isHome = key == AppNavKey.Home
    PlaceholderScreen(
        content =
            PlaceholderContent(
                title = title,
                navigation = spec.navigation,
                navigationLabel = navigationLabel,
                icon = spec.icon,
                empty = empty,
                withBottomBar = TopLevelDestination.ofRoot(key) != null,
                settingsLabel = if (isHome) stringResource(R.string.a11y_settings) else null,
                businessAction = if (isHome) stringResource(R.string.hub_entry_action) else null,
            ),
        uiState = PlaceholderUiState.Empty,
        onAction = { action ->
            when (action) {
                PlaceholderAction.Navigate -> navigator.back()

                PlaceholderAction.OpenSettings -> navigator.open(AppNavKey.Settings)

                PlaceholderAction.OpenBusiness -> navigator.open(AppNavKey.BusinessHub)

                PlaceholderAction.EmptyAction -> emptyTarget?.let(navigator::open)

                // Sin datos que leer todavía: no hay nada que reintentar.
                PlaceholderAction.Retry -> Unit
            }
        },
    )
}

@Composable
private fun emptyOf(key: AppNavKey, title: String): Pair<EmptyContent, AppNavKey?> =
    when (TopLevelDestination.ofRoot(key)) {
        TopLevelDestination.HOME ->
            EmptyContent(
                title = stringResource(R.string.empty_home_title),
                body = stringResource(R.string.empty_home_body),
                action = stringResource(R.string.empty_home_action),
            ) to AppNavKey.ProductEdit()

        TopLevelDestination.RECORDS ->
            EmptyContent(
                title = stringResource(R.string.empty_sales_title),
                body = stringResource(R.string.empty_sales_body),
                action = stringResource(R.string.empty_sales_action),
            ) to AppNavKey.NewSale()

        TopLevelDestination.PRODUCTS ->
            EmptyContent(
                title = stringResource(R.string.empty_catalog_title),
                body = stringResource(R.string.empty_catalog_body),
                action = stringResource(R.string.empty_catalog_action),
            ) to AppNavKey.ProductEdit()

        TopLevelDestination.NUMBERS ->
            EmptyContent(
                title = stringResource(R.string.empty_stats_title),
                action = stringResource(R.string.empty_stats_action),
            ) to AppNavKey.NewSale()

        null -> EmptyContent(title = title) to null
    }

/** Si el catálogo tiene un solo producto, contando los archivados: el que se acaba de guardar. */
private suspend fun hasOnlyOneProduct(access: DataAccess): Boolean = when (access) {
    is DataAccess.Ready -> {
        val products = access.ports.products
        products.observe(ArchiveFilter.ACTIVE).first().size +
            products.observe(ArchiveFilter.ARCHIVED).first().size == 1
    }

    is DataAccess.Rescue -> false
}
