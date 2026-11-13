package io.github.yesidrobayo.shopledger.ui.customer

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * La lista de clientes en la navegación. Abrir un cliente lleva a su detalle; `Programa de puntos`
 * abre su pantalla. Atrás con la hoja abierta la cierra primero.
 */
@Composable
fun CustomerListRoute(key: AppNavKey.Customers, navigator: AppNavigator) {
    val model = rememberAppViewModel(key = "customers") {
        CustomerListViewModel(CustomerUseCases.of(it))
    }
    when (model) {
        ScreenModel.Opening -> CustomerListScreen(CustomerListUiState(), {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val editor by viewModel.editor.editor.collectAsStateWithLifecycle()
            val pickContact = rememberContactPicker(viewModel.editor)
            ShowMessage(uiState.message) { viewModel.onAction(CustomerListAction.MessageShown) }
            BackHandler(enabled = editor != null) {
                viewModel.editor.onAction(CustomerEditorAction.Dismiss)
            }
            CustomerListScreen(uiState, { action ->
                when (action) {
                    CustomerListAction.Back -> navigator.back()

                    is CustomerListAction.OpenCustomer ->
                        navigator.open(AppNavKey.CustomerDetail(action.customerId))

                    CustomerListAction.OpenLoyalty -> navigator.open(AppNavKey.LoyaltyProgram)

                    is CustomerListAction.QueryChange, is CustomerListAction.FilterChange,
                    CustomerListAction.ClearFilters, CustomerListAction.Retry,
                    is CustomerListAction.NewCustomer, is CustomerListAction.Reactivate,
                    CustomerListAction.MessageShown,
                    -> viewModel.onAction(action)
                }
            })
            editor?.let {
                CustomerEditorSheet(it, { action ->
                    when (action) {
                        CustomerEditorAction.PickContact -> {
                            viewModel.editor.onAction(action)
                            pickContact()
                        }

                        is CustomerEditorAction.Name, is CustomerEditorAction.Phone,
                        is CustomerEditorAction.Email, is CustomerEditorAction.Notes,
                        CustomerEditorAction.Save, CustomerEditorAction.Dismiss,
                        -> viewModel.editor.onAction(action)
                    }
                })
            }
        }
    }
}

/**
 * El selector de contactos del sistema, que lleva el nombre y el teléfono del contacto elegido a la
 * hoja de [editor]. Pide solo un teléfono con `ACTION_PICK`: el sistema da permiso de leer esa sola
 * fila, así que la app no declara permiso de contactos ni ve ningún otro. Sin app de contactos, la
 * hoja lo avisa y sigue.
 *
 * Devuelve la función que lo abre.
 */
@Composable
fun rememberContactPicker(editor: CustomerEditorModel): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(PickPhoneContact) { uri ->
        if (uri != null) {
            scope.launch {
                val contact = withContext(Dispatchers.IO) { readContact(context, uri) }
                if (contact != null) editor.fillFromContact(contact.name, contact.phone)
            }
        }
    }
    return {
        try {
            launcher.launch(Unit)
        } catch (_: ActivityNotFoundException) {
            editor.contactsUnavailable()
        }
    }
}

/** Abre el selector de teléfonos de la app de contactos y devuelve la fila elegida. */
private object PickPhoneContact : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data.takeIf { resultCode == android.app.Activity.RESULT_OK }
}

/** El nombre y el teléfono de un contacto elegido; cualquiera de los dos puede faltar. */
private class PickedContact(val name: String?, val phone: String?)

/**
 * Lee el nombre y el teléfono de la fila que dio el selector, y nada más. Si la app de contactos no
 * deja leerla, no trae nada: la persona escribe los datos a mano.
 */
private fun readContact(context: Context, uri: Uri): PickedContact? {
    val resolver: ContentResolver = context.contentResolver
    val columns = arrayOf(
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.Phone.NUMBER,
    )
    return try {
        resolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            PickedContact(name = cursor.getString(0), phone = cursor.getString(1))
        }
    } catch (_: SecurityException) {
        // La app de contactos no compartió la fila: la hoja sigue sin cambios.
        null
    } catch (_: IllegalArgumentException) {
        // La fila no tiene esas columnas: la hoja sigue sin cambios.
        null
    }
}

/**
 * Muestra [message] con los mensajes de la navegación, que sobreviven a la pantalla.
 */
@Composable
private fun ShowMessage(message: CustomerMessage?, onShown: () -> Unit) {
    val messages = LocalAppMessages.current
    val text = message?.let {
        stringResource(
            when (it) {
                CustomerMessage.Saved -> R.string.customer_saved
                CustomerMessage.Reactivated -> R.string.customer_reactivated
                is CustomerMessage.Failed -> saveErrorText(it.outOfSpace)
            },
        )
    }
    val variant = when (message) {
        null, CustomerMessage.Saved, CustomerMessage.Reactivated -> SnackbarVariant.SUCCESS
        is CustomerMessage.Failed -> SnackbarVariant.ERROR
    }
    LaunchedEffect(message) {
        if (text != null) {
            messages.show(AppSnackbarVisuals(text, variant))
            onShown()
        }
    }
}
