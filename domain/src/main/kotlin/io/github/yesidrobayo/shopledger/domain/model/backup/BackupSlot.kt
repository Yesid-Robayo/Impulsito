package io.github.yesidrobayo.shopledger.domain.model.backup

/**
 * Una de las tres ranuras de la nube del teléfono: tres archivos fijos que la app reescribe en su
 * sitio, porque no puede crear un documento por copia sin abrir el selector cada vez.
 *
 * [A] y [B] se alternan y guardan lo reciente. [C] es el ancla: guarda una copia de hasta siete
 * días, para tener algo más viejo si lo reciente salió mal.
 *
 * El nombre del valor es la referencia que guarda el estado del respaldo para la cabeza y para un
 * conflicto: `A`, `B` o `C`, igual en cualquier idioma.
 */
enum class BackupSlot {
    A,
    B,
    C,
    ;

    companion object {
        /**
         * La ranura que nombra [fileRef], o `null` si no nombra ninguna.
         *
         * Con `null`, o con el nombre de un archivo de otro destino, no hay ranura: quien llama lo
         * trata como una cabeza desconocida, nunca como la ranura `A`.
         */
        fun fromFileRefOrNull(fileRef: String?): BackupSlot? =
            entries.firstOrNull { it.name == fileRef }
    }
}
