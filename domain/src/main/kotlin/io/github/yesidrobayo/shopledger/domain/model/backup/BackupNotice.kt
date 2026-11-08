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

    /**
     * Con la nube del teléfono, toca revisar en esa nube que la copia aparezca: pasaron 7 días desde
     * la última revisión o, sin ninguna, desde la primera entrega. Va aparte de la revisión como
     * estado porque un fallo o un día sin copia le ganan, y las entregas siguen igual.
     */
    HUMAN_CHECK_DUE,

    /**
     * Con WebDAV, pasaron 7 días sin leer una copia entera del servicio, contados desde la conexión
     * si nunca hubo una: con solo datos móviles, el centro del archivo nadie lo lee. El estado no
     * cambia, porque el tamaño y la muestra sí se comprobaron.
     */
    FULL_READ_OVERDUE,

    /**
     * Con WebDAV, el servicio informa un espacio libre en el que caben menos de 3 copias. Avisa antes
     * de que una copia no quepa; sin un tope informado no hay aviso.
     */
    SERVICE_NEARLY_FULL,
}
