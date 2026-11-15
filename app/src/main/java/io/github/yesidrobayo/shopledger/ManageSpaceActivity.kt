package io.github.yesidrobayo.shopledger

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.yesidrobayo.shopledger.navigation.AppOpenRequest

/**
 * Lo que abre el botón de borrar datos de los ajustes de Android, que según la versión dice
 * `Administrar espacio` o `Borrar almacenamiento`. No borra nada ni muestra nada propio: abre
 * `Almacenamiento y borrado` en la actividad principal, que pasa antes por el bloqueo si está
 * encendido, y se cierra. Así Android no ofrece borrar sin que la persona vea si tiene copia.
 *
 * Sin exportar y sin filtros: solo la abre el sistema, por su nombre en el manifiesto.
 */
class ManageSpaceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val open =
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        AppOpenRequest.ManageSpace.writeTo(open)
        startActivity(open)
        finish()
    }
}
