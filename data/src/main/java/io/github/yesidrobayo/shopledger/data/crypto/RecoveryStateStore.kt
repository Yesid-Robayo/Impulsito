package io.github.yesidrobayo.shopledger.data.crypto

import io.github.yesidrobayo.shopledger.data.destination.FlatJson
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Si el cifrado de las copias está activo y con qué código, en `recovery/state.json`, al lado del
 * sobre. Nunca lleva el código: solo su `key_id`, que no revela nada, y fechas en UTC.
 *
 * - Cada cambio se escribe con `.tmp`, se sincroniza y se renombra: tras un corte queda el estado
 *   de antes o el nuevo, nunca uno a medias.
 * - Un archivo que falta es "nunca activado". Uno que no se lee también se trata así al leer, pero
 *   no se pisa solo: el sobre manda al arrancar ([alignWith]).
 * - Una clave desconocida, de una versión más nueva, se ignora al leer.
 *
 * Una sola instancia por proceso: el cerrojo vive en ella.
 */
internal class RecoveryStateStore(
    private val directory: () -> File,
    private val ioContext: CoroutineContext,
) {
    private val mutex = Mutex()

    private fun file() = File(directory(), FILE_NAME)

    suspend fun read(): RecoveryState = mutex.withLock { withContext(ioContext) { readNow() } }

    /** Escribe [state] entero, de forma durable. Lanza `IOException` si el disco falla. */
    suspend fun write(state: RecoveryState) {
        mutex.withLock { withContext(ioContext) { writeNow(state) } }
    }

    /**
     * Lee, cambia y escribe el estado bajo el mismo cerrojo, para que nada se cuele entre la lectura
     * y la escritura. Solo escribe si [change] cambió algo. Devuelve el estado resultante, y lanza
     * `IOException` si el disco falla al escribir.
     */
    suspend fun update(change: (RecoveryState) -> RecoveryState): RecoveryState = mutex.withLock {
        withContext(ioContext) {
            val current = readNow()
            val changed = change(current)
            if (changed != current) writeNow(changed)
            changed
        }
    }

    /**
     * Alinea el estado con el sobre, al arrancar: el sobre manda, porque se escribe antes que el
     * estado al activar o cambiar el código. Si [sealedKeyIdHex] es el `key_id` que se apagó a
     * propósito, el cifrado sigue apagado: reiniciar nunca lo vuelve a encender. Devuelve el estado
     * resultante; solo escribe si cambió algo.
     */
    suspend fun alignWith(sealedKeyIdHex: String, nowUtcMillis: Long): RecoveryState =
        mutex.withLock {
            withContext(ioContext) {
                val current = readNow()
                val aligned =
                    when {
                        current.disabledKeyIdHex == sealedKeyIdHex -> current

                        current.enabled && current.keyIdHex == sealedKeyIdHex -> current

                        // Un sobre nuevo que el estado no alcanzó a ver: el corte fue tras sellar.
                        else ->
                            current.copy(
                                enabled = true,
                                keyIdHex = sealedKeyIdHex,
                                enabledAtUtcMillis = nowUtcMillis,
                                offConfirmedAtUtcMillis = null,
                                // Un código nuevo todavía no se comprobó.
                                lastCodeCheckAtUtcMillis = null,
                            )
                    }
                if (aligned != current) writeNow(aligned)
                aligned
            }
        }

    private fun readNow(): RecoveryState {
        val file = file()
        if (!file.isFile) return RecoveryState.NEVER_ENABLED
        val text =
            try {
                file.readText(Charsets.UTF_8)
            } catch (_: IOException) {
                return RecoveryState.NEVER_ENABLED
            }
        return RecoveryStateCodec.decode(text) ?: RecoveryState.NEVER_ENABLED
    }

    private fun writeNow(state: RecoveryState) {
        DurableFile.write(file(), RecoveryStateCodec.encode(state).toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val FILE_NAME = "state.json"
    }
}

/**
 * El estado del cifrado de las copias en este teléfono.
 *
 * @property enabled si las copias nuevas salen cifradas.
 * @property keyIdHex el `key_id` del código vigente, en hexadecimal, o `null` si nunca hubo uno.
 * @property enabledAtUtcMillis cuándo quedó activo con ese código.
 * @property offConfirmedAtUtcMillis cuándo la persona confirmó apagarlo, si lo hizo.
 * @property disabledKeyIdHex el `key_id` que se apagó a propósito: un sobre con ese `key_id` no lo
 *   vuelve a encender al arrancar ni al restaurar en este teléfono.
 * @property lastCodeCheckAtUtcMillis la última vez que la persona escribió bien el código vigente,
 *   en la prueba periódica o en `Probar el código`. Un código nuevo empieza sin comprobar.
 */
internal data class RecoveryState(
    val enabled: Boolean,
    val keyIdHex: String?,
    val enabledAtUtcMillis: Long?,
    val offConfirmedAtUtcMillis: Long?,
    val disabledKeyIdHex: String?,
    val lastCodeCheckAtUtcMillis: Long? = null,
) {
    init {
        require(keyIdHex == null || isKeyIdHex(keyIdHex)) { "key_id con forma inválida" }
        require(disabledKeyIdHex == null || isKeyIdHex(disabledKeyIdHex)) {
            "key_id con forma inválida"
        }
        require(!enabled || keyIdHex != null) { "Activo sin código" }
    }

    companion object {
        val NEVER_ENABLED = RecoveryState(false, null, null, null, null)

        private const val KEY_ID_HEX_LENGTH = 2 * RecoveryCodec.KEY_ID_BYTES

        fun isKeyIdHex(text: String): Boolean =
            text.length == KEY_ID_HEX_LENGTH && text.all { it in '0'..'9' || it in 'a'..'f' }
    }
}

/** `state.json` en JSON plano y estricto. Un archivo que no tiene esta forma no se usa. */
internal object RecoveryStateCodec {
    private const val VERSION = "1"
    private const val KEY_VERSION = "version"
    private const val KEY_ENABLED = "encryption_enabled"
    private const val KEY_KEY_ID = "key_id"
    private const val KEY_ENABLED_AT = "enabled_at_utc_ms"
    private const val KEY_OFF_CONFIRMED_AT = "encryption_off_confirmed_at"
    private const val KEY_LAST_CODE_CHECK_AT = "last_code_check_at_utc_ms"
    private const val KEY_DISABLED_KEY_ID = "disabled_key_id"

    fun encode(state: RecoveryState): String {
        val members = mutableListOf<Pair<String, FlatJson.Value>>()
        members += KEY_VERSION to FlatJson.Value.Number(VERSION)
        members += KEY_ENABLED to FlatJson.Value.Bool(state.enabled.toString())
        state.keyIdHex?.let { members += KEY_KEY_ID to FlatJson.Value.Text(it) }
        state.enabledAtUtcMillis?.let {
            members +=
                KEY_ENABLED_AT to FlatJson.Value.Number(it.toString())
        }
        state.lastCodeCheckAtUtcMillis?.let {
            members += KEY_LAST_CODE_CHECK_AT to FlatJson.Value.Number(it.toString())
        }
        state.offConfirmedAtUtcMillis?.let {
            members += KEY_OFF_CONFIRMED_AT to FlatJson.Value.Number(it.toString())
        }
        state.disabledKeyIdHex?.let { members += KEY_DISABLED_KEY_ID to FlatJson.Value.Text(it) }
        return FlatJson.write(members)
    }

    fun decode(text: String): RecoveryState? {
        val members = FlatJson.parse(text) ?: return null
        if ((members[KEY_VERSION] as? FlatJson.Value.Number)?.text != VERSION) return null
        val enabled =
            (members[KEY_ENABLED] as? FlatJson.Value.Bool)?.text?.toBooleanStrictOrNull()
                ?: return null
        val keyId = hex(members, KEY_KEY_ID) ?: return null
        val enabledAt = millis(members, KEY_ENABLED_AT) ?: return null
        val offConfirmedAt = millis(members, KEY_OFF_CONFIRMED_AT) ?: return null
        val lastCodeCheckAt = millis(members, KEY_LAST_CODE_CHECK_AT) ?: return null
        val disabledKeyId = hex(members, KEY_DISABLED_KEY_ID) ?: return null
        if (enabled && keyId.value == null) return null
        return RecoveryState(
            enabled = enabled,
            keyIdHex = keyId.value,
            enabledAtUtcMillis = enabledAt.value,
            offConfirmedAtUtcMillis = offConfirmedAt.value,
            lastCodeCheckAtUtcMillis = lastCodeCheckAt.value,
            disabledKeyIdHex = disabledKeyId.value,
        )
    }

    /** Un campo opcional ya revisado: [value] es `null` si no estaba. */
    private class Optional<T>(val value: T?)

    /** El `key_id` de [key], o `null` si está con otra forma. */
    private fun hex(members: Map<String, FlatJson.Value>, key: String): Optional<String>? =
        when (val value = members[key]) {
            null -> Optional(null)

            is FlatJson.Value.Text -> if (RecoveryState.isKeyIdHex(
                    value.text,
                )
            ) {
                Optional(value.text)
            } else {
                null
            }

            is FlatJson.Value.Number, is FlatJson.Value.Bool -> null
        }

    /** El instante de [key], o `null` si está con otra forma. */
    private fun millis(members: Map<String, FlatJson.Value>, key: String): Optional<Long>? =
        when (val value = members[key]) {
            null -> Optional(null)

            is FlatJson.Value.Number -> value.text.toLongOrNull()?.takeIf {
                it >= 0
            }?.let { Optional(it) }

            is FlatJson.Value.Text, is FlatJson.Value.Bool -> null
        }
}
