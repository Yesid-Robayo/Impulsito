package io.github.yesidrobayo.shopledger.domain.usecase.safety

import io.github.yesidrobayo.shopledger.domain.model.safety.CloudAccountCheck
import io.github.yesidrobayo.shopledger.domain.model.safety.CloudAccountKind
import io.github.yesidrobayo.shopledger.domain.model.safety.CloudAccountMarks
import io.github.yesidrobayo.shopledger.domain.port.CloudAccountChecklistPort

/**
 * La lista corta de la cuenta de la nube: tres puntos que la persona marca, porque la app no puede
 * saberlo. Las marcas valen para el tipo de destino con que se hicieron: al cambiar de WebDAV a la
 * nube del teléfono, o al revés, la cuenta es otra y la lista vuelve a empezar.
 */
class CloudAccountChecklistUseCase(private val port: CloudAccountChecklistPort) {
    /** Los puntos marcados para [kind]. */
    suspend fun checked(kind: CloudAccountKind): Set<CloudAccountCheck> =
        port.load()?.checkedFor(kind).orEmpty()

    /**
     * Marca [check] para [kind] si [marked], o lo desmarca, y devuelve cómo quedó la lista, o
     * nulo si no se pudo guardar: entonces sigue como estaba. Marcar con otro tipo de destino
     * empieza la lista de cero.
     */
    suspend fun mark(
        kind: CloudAccountKind,
        check: CloudAccountCheck,
        marked: Boolean,
    ): Set<CloudAccountCheck>? {
        val current = checked(kind)
        val next = if (marked) current + check else current - check
        return if (port.save(CloudAccountMarks(kind, next))) next else null
    }
}
