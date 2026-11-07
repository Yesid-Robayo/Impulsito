package io.github.yesidrobayo.shopledger.domain.model.backup

/**
 * Un aviso del respaldo que no es un estado: se calcula aparte del estado y se muestra junto a
 * cualquiera, para que ninguna falla lo esconda. Ninguno detiene una copia ni deja un fallo.
 */
enum class BackupNotice {
    /**
     * Queda poco espacio en el teléfono: menos que el mayor entre 200 MiB y tres veces la base. Avisa
     * antes de que la copia no se pueda generar.
     */
    LOW_DEVICE_STORAGE,

    /**
     * La copia se acerca al tamaño más grande que la app sube y restaura: avisa con años de margen,
     * antes de que una copia deje de subir por grande.
     */
    COPY_NEAR_LIMIT,
}
