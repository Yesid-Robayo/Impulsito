package io.github.yesidrobayo.shopledger.data.destination.slots

import java.io.IOException

/**
 * Dónde vive el estado de las ranuras. La única implementación de la app es un archivo con la
 * durabilidad del diario: una escritura que vuelve ya está en el disco, también tras un corte de
 * energía. Las pruebas usan uno en memoria.
 */
internal interface SlotStateStore {
    /** El estado guardado, o por qué no hay ninguno que usar. */
    suspend fun load(): SlotStateLoad

    /**
     * Guarda [state] entero. Cuando vuelve, sobrevive a un corte de energía: un corte a mitad deja
     * el estado de antes, nunca uno a medias.
     *
     * @throws IOException si no se pudo escribir. El estado de antes sigue intacto.
     */
    suspend fun save(state: SlotState)

    /**
     * Borra el estado, al soltar las ranuras porque la persona desconectó o cambió de destino. Un
     * archivo que no existe ya cuenta como borrado. Devuelve `false` si quedó algo.
     */
    suspend fun delete(): Boolean
}

/** Lo que se encontró al leer el estado de las ranuras. */
internal sealed interface SlotStateLoad {
    data class Loaded(val state: SlotState) : SlotStateLoad

    /**
     * No hay estado. Con un destino de ranuras conectado nunca significa ranuras vacías: la app ya
     * no sabe cuál es la cabeza, y no escribe en ninguna.
     */
    data object Missing : SlotStateLoad

    /** Hay un archivo, pero no se lee como un estado válido. Igual que [Missing]. */
    data object Unreadable : SlotStateLoad
}
