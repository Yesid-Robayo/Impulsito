package io.github.yesidrobayo.shopledger.harness

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.data.harness.BackupHarness
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.DefaultColorPalette

/**
 * El arnés de depuración del respaldo, solo en `debug`: conectar una cuenta WebDAV de prueba,
 * preparar tres ranuras, registrar cambios inventados, `Respaldar ahora`, restaurar desde un archivo
 * y ver el estado del respaldo, mientras las pantallas de la app no existen.
 *
 * No se exporta ni tiene filtros: solo la abre la propia app, cuando la actividad principal recibe
 * el extra de [io.github.yesidrobayo.shopledger.DebugEntry]. Las ranuras se crean una por una con el
 * selector del sistema, en orden A, B y C, y se entregan juntas.
 *
 * Para una cuenta real, la cuenta entra por la entrada estándar de `adb` al archivo privado del
 * arnés, nunca como texto escrito en la pantalla ni en una línea de comandos:
 *
 * `adb shell "run-as <paquete> sh -c 'umask 077; cat > no_backup/harness-credential'" < archivo`
 *
 * y luego `Conectar con el archivo`, que lo lee y lo borra.
 */
class BackupHarnessActivity : AppCompatActivity() {
    private val viewModel: BackupHarnessViewModel by viewModels {
        viewModelFactory {
            initializer {
                val container = (application as ShopLedgerApp).container
                val harness =
                    BackupHarness(
                        graph = container.data,
                        context = application,
                        clock = container.clock,
                        ioContext = container.dispatchers.io,
                    )
                BackupHarnessViewModel(
                    DataHarnessPort(
                        data = container.data,
                        harness = harness,
                        clock = container.clock,
                        appName = getString(R.string.app_name),
                        supplierPrefix = getString(R.string.harness_supplier_prefix),
                    ),
                )
            }
        }
    }

    /** Los documentos de las ranuras ya creados, en orden. Son URI, no secretos. */
    private val createdSlots = ArrayList<String>()

    private val createSlot =
        registerForActivityResult(ActivityResultContracts.CreateDocument(SLOT_MIME_TYPE)) { uri ->
            if (uri == null) {
                // La persona canceló a mitad: nada se prepara, y los archivos creados quedan sin uso.
                createdSlots.clear()
            } else {
                createdSlots += uri.toString()
                if (createdSlots.size < SLOT_NAMES.size) {
                    launchNextSlot()
                } else {
                    viewModel.prepareSlots(createdSlots.toList())
                    createdSlots.clear()
                }
            }
        }

    private val pickRestoreFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.restoreFromFile(it.toString()) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getStringArrayList(CREATED_SLOTS)?.let(createdSlots::addAll)
        enableEdgeToEdge()
        val callbacks =
            HarnessCallbacks(
                acceptPolicy = viewModel::acceptPolicy,
                connect = viewModel::connectWebDav,
                connectFromFile = viewModel::connectWebDavFromFile,
                prepareSlots = {
                    createdSlots.clear()
                    launchNextSlot()
                },
                recordChanges = viewModel::recordInventedChanges,
                backupNow = viewModel::backupNow,
                pickRestoreFile = { pickRestoreFile.launch(arrayOf(ANY_FILE)) },
                restoreNewest = viewModel::restoreNewestCopy,
                answer = viewModel::answer,
                cancelRestore = viewModel::cancelRestore,
                restart = ::restartForRestore,
            )
        setContent {
            val state by viewModel.uiState.collectAsState()
            AppTheme(palette = DefaultColorPalette, darkTheme = isSystemInDarkTheme()) {
                BackupHarnessScreen(state, callbacks)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(CREATED_SLOTS, createdSlots)
    }

    private fun launchNextSlot() {
        createSlot.launch(SLOT_NAMES[createdSlots.size])
    }

    /**
     * Reinicia el proceso con el diario ya escrito: el arranque hace el reemplazo antes de abrir la
     * base, y el arnés vuelve a abrirse para mostrar el estado.
     */
    private fun restartForRestore() {
        startActivity(Intent.makeRestartActivityTask(ComponentName(this, javaClass)))
        Runtime.getRuntime().exit(0)
    }

    private companion object {
        const val SLOT_MIME_TYPE = "application/octet-stream"
        const val ANY_FILE = "*/*"
        const val CREATED_SLOTS = "created_slots"

        /** Los nombres de las tres ranuras, en orden A, B y C. */
        val SLOT_NAMES =
            listOf(
                "shopledger-backup-a.slcopy",
                "shopledger-backup-b.slcopy",
                "shopledger-backup-c.slcopy",
            )
    }
}
