package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.backup.FullCopyExport

/**
 * Exportar una copia completa a un archivo que eligió la persona con el selector del sistema. Usa
 * los mismos pasos que una copia del respaldo, sin red, y solo lee: no cambia los datos, el estado
 * del respaldo ni el destino conectado. Lo implementa la capa de datos.
 */
interface FullCopyExportPort {
    /** Escribe la copia en el documento [documentUri], ya creado por el selector, y la relee. */
    suspend fun export(documentUri: String): FullCopyExport
}
