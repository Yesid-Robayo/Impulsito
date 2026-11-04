package io.github.yesidrobayo.shopledger.data.local.migration

/**
 * Todos los saltos de versión de la base de la app, del primero al último.
 *
 * Está vacía: el esquema 1 nace completo y no migra, y se edita sin migraciones mientras no haya
 * datos reales. Desde la primera base real, cada cambio de esquema sube la versión de
 * `AppDatabase` y agrega aquí su salto, con su JSON exportado y sus pruebas, como muestra la
 * plantilla de las pruebas del dispositivo.
 *
 * Es la única lista: la usan la base activa al abrirse y la base temporal que migra una copia
 * candidata antes de reemplazar la activa, así que las dos recorren los mismos saltos.
 *
 * Quien abre la base al arrancar decide antes de llamar a `AppDatabase.open`: si el `user_version`
 * de la base, leído con `PRAGMA`, es menor que el de la app, primero hace la copia previa y solo
 * entonces abre, que es cuando Room migra. Si un salto falla, `open` relanza la misma excepción del
 * salto, de cualquier tipo, y la base queda en la versión anterior, con todas sus filas.
 */
internal object AppMigrations {
    val all: List<CheckedMigration> = emptyList()
}
