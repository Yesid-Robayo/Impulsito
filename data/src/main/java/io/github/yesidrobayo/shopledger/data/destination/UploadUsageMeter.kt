package io.github.yesidrobayo.shopledger.data.destination

/**
 * Recibe los bytes que un destino envió al subir una copia, para el consumo del mes. Se llama una
 * vez por subida, al terminar o al cortarse: lo enviado ya se gastó, llegue o no la copia entera.
 */
internal fun interface UploadUsageMeter {
    suspend fun sent(bytes: Long)

    companion object {
        /** No anota nada: para conectar, probar la conexión y las pruebas. */
        val None: UploadUsageMeter = UploadUsageMeter {}
    }
}
