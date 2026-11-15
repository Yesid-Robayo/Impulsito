package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.erase.EraseFacts
import io.github.yesidrobayo.shopledger.domain.model.erase.ErasePhase
import io.github.yesidrobayo.shopledger.domain.model.erase.RemoteEraseResult

/**
 * Lo que `Borrar mis datos` hace con los archivos, el destino y el teléfono. Lo implementa la capa
 * de datos; las decisiones las toma el caso de uso con `EraseRules`.
 */
interface EraseMyDataPort {
    /** El destino, la red y si hay una restauración en curso. Solo lee. */
    suspend fun facts(): EraseFacts

    /**
     * El borrado ya confirmado que quedó en espera, o `null` si no hay. Un registro que no se
     * entiende cuenta como [ErasePhase.REMOTE]: nunca hace borrar lo local sin otra confirmación.
     */
    suspend fun pending(): ErasePhase?

    /** Guarda [phase] en el teléfono, de forma durable, antes de dar el paso siguiente. */
    suspend fun save(phase: ErasePhase)

    /**
     * Borra del destino conectado las copias, sus `.slmeta` y las fotos con WebDAV, o los tres
     * archivos con ranuras, y nada que la app no haya escrito. Que algo ya no esté es éxito, así
     * que repetirlo tras un corte sigue donde quedó. No toca nada de este teléfono.
     */
    suspend fun deleteRemote(): RemoteEraseResult

    /**
     * Borra todo lo de este teléfono: la credencial sellada y los permisos de las ranuras, la
     * base, las fotos, lo de `noBackupFilesDir` y, al final, el registro del borrado y la marca de
     * que la base existió. Devuelve `false` si algo no se pudo borrar: el registro queda, y se
     * retoma.
     */
    suspend fun deleteLocal(): Boolean
}
