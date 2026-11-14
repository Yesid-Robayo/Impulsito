package io.github.yesidrobayo.shopledger.data.media

import io.github.yesidrobayo.shopledger.data.destination.FlatJson

/**
 * El `.slmeta` de una foto en el servicio: en claro, sin nada del negocio ni del teléfono.
 * `sl_kind=media` la separa de las copias aunque alguien la mueva de carpeta. Una foto cifrada lleva
 * `sl_format=2` y su `sl_key_id`, como una copia cifrada; la cabecera autenticada manda sobre esto.
 */
internal object MediaMeta {
    private const val PLAIN_FORMAT = "1"
    private const val SEALED_FORMAT = "2"

    /** El `.slmeta` de la foto [sha256] en la forma [form], con [fileSizeBytes] del archivo subido. */
    fun of(sha256: String, form: MediaForm, fileSizeBytes: Long): ByteArray {
        val common =
            listOf(
                "sl_kind" to FlatJson.Value.Text("media"),
                "sl_format" to FlatJson.Value.Number(
                    when (form) {
                        MediaForm.Plain -> PLAIN_FORMAT
                        is MediaForm.Sealed -> SEALED_FORMAT
                    },
                ),
                "sl_media_sha256" to FlatJson.Value.Text(sha256),
                "sl_file_size" to FlatJson.Value.Number(fileSizeBytes.toString()),
            )
        val specific =
            when (form) {
                // En claro, la suma del archivo es la de la foto.
                MediaForm.Plain -> "sl_sha256" to FlatJson.Value.Text(sha256)

                is MediaForm.Sealed -> "sl_key_id" to FlatJson.Value.Text(form.keyIdHex)
            }
        return FlatJson.write(common + specific).encodeToByteArray()
    }
}
