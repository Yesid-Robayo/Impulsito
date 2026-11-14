package io.github.yesidrobayo.shopledger.data.media

/**
 * Cómo está una foto en el servicio: en claro, como `.webp`, o cifrada con un código, como `.slmd`.
 *
 * Una foto cuenta como guardada solo en la forma que pide el cifrado vigente: al activarlo, cambiar
 * el código o apagarlo, cada foto se sube otra vez y la forma anterior se borra solo tras verificar
 * la nueva.
 */
internal sealed interface MediaForm {
    /** En claro: el cifrado de las copias está apagado. */
    data object Plain : MediaForm

    /** Cifrada con el código de [keyIdHex], el mismo de las copias cifradas. */
    data class Sealed(val keyIdHex: String) : MediaForm

    companion object {
        private const val KEY_ID_HEX_LENGTH = 16

        /** El texto de [form] en `media-sync.txt`: vacío en claro, o el `key_id`. */
        fun encode(form: MediaForm): String = when (form) {
            Plain -> ""
            is Sealed -> form.keyIdHex
        }

        /** La forma escrita por [encode], o `null` si el texto no es una. */
        fun decode(text: String): MediaForm? = when {
            text.isEmpty() -> Plain

            text.length == KEY_ID_HEX_LENGTH && text.all { it in '0'..'9' || it in 'a'..'f' } ->
                Sealed(text)

            else -> null
        }
    }
}
