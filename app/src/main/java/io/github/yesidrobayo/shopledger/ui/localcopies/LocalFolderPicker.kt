package io.github.yesidrobayo.shopledger.ui.localcopies

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts

/**
 * El selector de carpetas del sistema para el segundo destino: solo almacenamiento local, y abre en
 * `Documents`, o en `Documents/<app_name>/` para buscar una copia tras reinstalar. La carpeta que
 * vuelve la revisa la capa de datos: `EXTRA_LOCAL_ONLY` solo no alcanza.
 */
class LocalFolderPicker : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)

    companion object {
        private const val AUTHORITY = "com.android.externalstorage.documents"
        private const val DOCUMENTS = "primary:Documents"

        /** `Documents`, en la memoria del teléfono: donde abre el selector. */
        fun documents(): Uri = DocumentsContract.buildDocumentUri(AUTHORITY, DOCUMENTS)

        /** `Documents/<app_name>/`: donde quedan las copias de una instalación anterior. */
        fun appFolder(appName: String): Uri =
            DocumentsContract.buildDocumentUri(AUTHORITY, "$DOCUMENTS/$appName")
    }
}
