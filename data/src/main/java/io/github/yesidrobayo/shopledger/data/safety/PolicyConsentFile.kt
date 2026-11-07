package io.github.yesidrobayo.shopledger.data.safety

import io.github.yesidrobayo.shopledger.data.crypto.DurableFile
import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyVersions
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/** Lo que se leyó de `consents.json`. */
internal sealed interface ConsentLoad {
    /** No hay archivo: en esta instalación nunca se aceptó nada. */
    data object Missing : ConsentLoad

    /**
     * Hay un archivo, pero no dice con claridad qué se aceptó. Nunca se usa a medias: cuenta como
     * una aceptación que hay que pedir otra vez.
     */
    data object Unreadable : ConsentLoad

    /** Las versiones que la persona aceptó. */
    data class Accepted(val versions: PolicyVersions) : ConsentLoad
}

/**
 * La aceptación de la política y los términos en `consents.json`, dentro de [directory], que en la
 * app es `noBackupFilesDir`: es de la instalación, no del negocio, así que no viaja en una copia ni
 * el sistema la pasa a otro teléfono.
 *
 * Se escribe con `.tmp`, sincronización, renombrado y sincronización de la carpeta: un corte de
 * energía deja la aceptación de antes o la nueva, nunca una a medias. Guarda solo las versiones,
 * sin fecha ni nada de la persona.
 *
 * El archivo puede guardar otras marcas de la instalación. Escribir la aceptación conserva las
 * claves que no son suyas, para que aceptar no borre lo que la persona marcó en otra pantalla.
 *
 * @param directory da la carpeta. Pedirla toca el disco: se pide en [ioContext].
 */
internal class PolicyConsentFile(
    private val directory: () -> File,
    private val ioContext: CoroutineContext,
) {
    private fun file() = File(directory(), FILE_NAME)

    /** Lo aceptado. Nunca lanza por el contenido: lo que no se entiende es ilegible. */
    suspend fun load(): ConsentLoad = withContext(ioContext) {
        val file = file()
        if (!file.exists()) return@withContext ConsentLoad.Missing
        val root = readObject(file) ?: return@withContext ConsentLoad.Unreadable
        val privacy = root.version(PRIVACY_POLICY_VERSION)
        val terms = root.version(TERMS_OF_USE_VERSION)
        if (privacy == null || terms == null) return@withContext ConsentLoad.Unreadable
        ConsentLoad.Accepted(PolicyVersions(privacyPolicy = privacy, termsOfUse = terms))
    }

    /**
     * Deja [versions] como lo aceptado, con las demás claves del archivo intactas. Si el archivo no
     * se entiende, se reemplaza entero: no hay nada que conservar de él.
     *
     * @throws IOException si no quedó en el disco. Entonces el archivo sigue como estaba.
     */
    suspend fun save(versions: PolicyVersions) = withContext(ioContext) {
        val file = file()
        val root = (if (file.exists()) readObject(file) else null) ?: JSONObject()
        root.put(PRIVACY_POLICY_VERSION, versions.privacyPolicy)
        root.put(TERMS_OF_USE_VERSION, versions.termsOfUse)
        DurableFile.write(file, root.toString().toByteArray(Charsets.UTF_8))
    }

    /** El objeto del archivo, o nulo si no es un objeto JSON de un tamaño razonable. */
    private fun readObject(file: File): JSONObject? {
        if (!file.isFile || file.length() > MAX_BYTES) return null
        return try {
            JSONObject(file.readText(Charsets.UTF_8))
        } catch (_: JSONException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    /**
     * La versión guardada en [key], solo si es un número entero desde la primera versión. Un texto,
     * un decimal o un número fuera de rango no se interpretan.
     */
    private fun JSONObject.version(key: String): Int? =
        (opt(key) as? Int)?.takeIf { it >= PolicyVersions.FIRST }

    companion object {
        /** El nombre del archivo dentro de `noBackupFilesDir`. */
        const val FILE_NAME = "consents.json"

        /** La versión aceptada de la política de privacidad. */
        const val PRIVACY_POLICY_VERSION = "privacy_policy_version"

        /** La versión aceptada de los términos de uso. */
        const val TERMS_OF_USE_VERSION = "terms_of_use_version"

        /** Unas pocas claves caben de sobra: un archivo más grande no es de la app. */
        private const val MAX_BYTES = 16L * 1024
    }
}
