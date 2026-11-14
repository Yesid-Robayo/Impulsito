package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.imports.ImportFileRead
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator

/**
 * Lee el archivo que eligió la persona para importar, como entrada no confiable: con los topes de
 * tamaño, filas, columnas y celdas comprobados en flujo, y sin interpretar nada. Lo implementa
 * `:data`.
 *
 * No escribe en la base ni copia el archivo: lo lee del documento que dio el selector y lo suelta.
 * Lee el disco, así que nunca corre en el hilo principal, y se puede cancelar mientras lee.
 */
interface ImportFileReader {
    /**
     * Lee [source], el documento que dio el selector del sistema. [source] es un identificador
     * opaco: nunca se guarda ni se registra. Un CSV se lee con [separator], o con el que más
     * aparezca en su primera fila si es `null`, y [preferred], el de los ajustes, para los
     * empates. [onRows] recibe cuántas filas van leídas, para mostrar el progreso.
     */
    suspend fun read(
        source: String,
        separator: CsvSeparator?,
        preferred: CsvSeparator,
        onRows: (Int) -> Unit,
    ): ImportFileRead
}
