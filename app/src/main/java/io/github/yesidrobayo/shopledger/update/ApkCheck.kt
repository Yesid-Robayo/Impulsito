package io.github.yesidrobayo.shopledger.update

/**
 * Lo que dice `Verificar un APK` de un archivo elegido. La app nunca lo instala: solo lo compara
 * con la app instalada.
 */
sealed interface ApkCheckResult {
    /**
     * El archivo es de esta app, firmado con su mismo certificado.
     *
     * @property versionName la versión que declara el archivo, o nula si no la trae.
     * @property versionCode el número de compilación que declara.
     * @property sha256 la huella SHA-256 del archivo entero, en hexadecimal en minúsculas.
     * @property older si es de una versión anterior a la instalada: Android no lo instala encima.
     */
    data class Match(
        val versionName: String?,
        val versionCode: Long,
        val sha256: String,
        val older: Boolean,
    ) : ApkCheckResult

    /**
     * Otra firma, u otra app: no se instala, y la app instalada no se desinstala.
     *
     * @property sha256 la huella SHA-256 del archivo entero, en hexadecimal en minúsculas.
     */
    data class Mismatch(val sha256: String) : ApkCheckResult

    /** Se leyó entero, pero Android no lo reconoce como un APK. Uno truncado cae aquí. */
    data object NotApk : ApkCheckResult

    /** No se pudo leer, o pasó el límite de tamaño. */
    data object ReadFailed : ApkCheckResult
}

/**
 * La app instalada, como la muestra `Actualizaciones y verificar APK`.
 *
 * @property versionName su versión, o nula si el sistema no la da.
 * @property versionCode su número de compilación.
 * @property fingerprint la huella SHA-256 de su certificado, en grupos de dos cifras separados por
 *   `:`, o nula si no se pudo leer. Con varios firmantes, una huella por línea.
 */
data class InstalledApp(val versionName: String?, val versionCode: Long, val fingerprint: String?)
