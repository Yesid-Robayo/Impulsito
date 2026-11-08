package io.github.yesidrobayo.shopledger.data.harness

import java.io.File
import java.io.IOException

/**
 * La cuenta WebDAV de prueba que llega en un archivo privado de la app, solo en `debug`.
 *
 * Existe para conectar una cuenta real desde un emulador sin escribir la contraseña en la pantalla
 * ni en una línea de comandos: el archivo entra por la entrada estándar de `adb`, dentro de la
 * carpeta sin copia de la app, y se borra apenas se lee, pase lo que pase con la conexión. La
 * contraseña sigue el mismo camino que la de la pantalla: memoria, prueba de conexión y credencial
 * sellada.
 *
 * El formato son líneas `clave=valor`, con las claves [URL_KEY], [USER_KEY] y [PASSWORD_KEY]. Las
 * líneas vacías y las que empiezan con `#` se saltan. El valor es todo lo que sigue al primer `=`,
 * sin escapes, para que una contraseña con caracteres raros llegue igual.
 */
internal object HarnessCredentialFile {
    /** El nombre del archivo, dentro de la carpeta sin copia de la app. */
    const val FILE_NAME = "harness-credential"

    const val URL_KEY = "webdav.url"
    const val USER_KEY = "webdav.user"
    const val PASSWORD_KEY = "webdav.password"

    /** Lo que el archivo deja leer. */
    sealed interface Read {
        /** No había archivo. */
        data object Missing : Read

        /** El archivo no se pudo leer o le falta alguna clave. Ya se borró. */
        data object Invalid : Read

        /** La cuenta del archivo, que ya se borró. */
        class Account(val url: String, val username: String, val password: String) : Read {
            // Nada de la cuenta va a un registro ni a un mensaje de error.
            override fun toString(): String = "Account"
        }
    }

    /**
     * Lee [file] y lo borra enseguida, también si no sirve. Corre en el hilo de quien llama: el
     * arnés lo llama desde su contexto de disco.
     */
    fun readAndDelete(file: File): Read {
        if (!file.exists()) return Read.Missing
        val lines =
            try {
                file.readLines(Charsets.UTF_8)
            } catch (_: IOException) {
                null
            } finally {
                file.delete()
            }
        val values = lines?.let(::parse) ?: return Read.Invalid
        val url = values[URL_KEY]
        val username = values[USER_KEY]
        val password = values[PASSWORD_KEY]
        return if (url.isNullOrEmpty() || username.isNullOrEmpty() || password.isNullOrEmpty()) {
            Read.Invalid
        } else {
            Read.Account(url, username, password)
        }
    }

    private fun parse(lines: List<String>): Map<String, String> = lines.asSequence()
        .map { it.trimEnd('\r') }
        .filter { it.isNotBlank() && !it.trimStart().startsWith("#") && '=' in it }
        .associate { line -> line.substringBefore('=').trim() to line.substringAfter('=') }
}
