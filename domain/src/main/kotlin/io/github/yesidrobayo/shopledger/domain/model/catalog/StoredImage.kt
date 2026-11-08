package io.github.yesidrobayo.shopledger.domain.model.catalog

/**
 * Una imagen ya recodificada y guardada en su archivo, lista para que un caso de uso la anote en la
 * base. El archivo se llama como su contenido, [sha256], así que dos imágenes iguales son un solo
 * archivo; la base nunca guarda su ruta.
 *
 * Lanza [IllegalArgumentException] si [sha256] no son 64 hexadecimales en minúscula, o si alguna
 * medida no es positiva.
 */
data class StoredImage(
    val sha256: String,
    val mimeType: String,
    val widthPx: Int,
    val heightPx: Int,
    val sizeBytes: Long,
) {
    init {
        require(
            sha256.length == SHA256_HEX_LENGTH && sha256.all {
                it in '0'..'9' || it in 'a'..'f'
            },
        ) {
            "La suma de una imagen son 64 hexadecimales en minúscula"
        }
        require(widthPx > 0 && heightPx > 0 && sizeBytes > 0) {
            "Una imagen guardada tiene medidas"
        }
    }

    /** El nombre de su archivo, dentro de la carpeta de imágenes de la app. */
    val fileName: String get() = fileNameOf(sha256)

    companion object {
        private const val SHA256_HEX_LENGTH = 64

        /** La carpeta de las imágenes, dentro de los archivos privados de la app. */
        const val FOLDER: String = "media"

        /** El nombre del archivo de la imagen con la suma [sha256]: todas se guardan en WebP. */
        fun fileNameOf(sha256: String): String = "$sha256.webp"
    }
}
