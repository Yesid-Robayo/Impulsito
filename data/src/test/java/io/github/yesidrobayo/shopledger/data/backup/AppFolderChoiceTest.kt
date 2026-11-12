package io.github.yesidrobayo.shopledger.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * La carpeta de la app dentro de la elegida: la elegida si ya se llama como la app, la de la app o
 * de un nombre anterior si ya existe dentro, y si no, una nueva con el nombre de la app. Así al
 * elegir `Documents` las copias van en `Documents/<app_name>/`, y en una tarjeta sin `Documents`,
 * dentro de la carpeta que la persona elija o cree.
 */
class AppFolderChoiceTest {
    private val names = listOf("Tienda", "Anterior")

    @Test
    fun theChosenFolderIsUsedWhenItIsTheAppFolder() {
        assertEquals(
            AppFolderChoice.Choice.UseChosen,
            AppFolderChoice.choose("Tienda", listOf("Otra"), names),
        )
        assertEquals(
            AppFolderChoice.Choice.UseChosen,
            AppFolderChoice.choose("Anterior", emptyList(), names),
        )
    }

    @Test
    fun insideDocumentsTheAppFolderIsUsedOrAPreviousOne() {
        assertEquals(
            AppFolderChoice.Choice.UseChild("Tienda"),
            AppFolderChoice.choose("Documents", listOf("Anterior", "Tienda"), names),
        )
        assertEquals(
            AppFolderChoice.Choice.UseChild("Anterior"),
            AppFolderChoice.choose("Documents", listOf("Fotos", "Anterior"), names),
        )
    }

    @Test
    fun withoutItTheAppFolderIsCreated() {
        assertEquals(
            AppFolderChoice.Choice.CreateChild("Tienda"),
            AppFolderChoice.choose("Documents", listOf("Fotos"), names),
        )
        assertEquals(
            AppFolderChoice.Choice.CreateChild("Tienda"),
            AppFolderChoice.choose(null, emptyList(), names),
        )
    }

    @Test
    fun withoutTheAppNameThereIsNoFolder() {
        assertThrows(IllegalArgumentException::class.java) {
            AppFolderChoice.choose("Documents", emptyList(), emptyList())
        }
    }
}
