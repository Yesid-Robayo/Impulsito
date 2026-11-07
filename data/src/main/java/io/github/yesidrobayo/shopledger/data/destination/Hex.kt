package io.github.yesidrobayo.shopledger.data.destination

import java.security.MessageDigest

private const val HEX_DIGITS = "0123456789abcdef"

/**
 * Los bytes en hexadecimal en minúscula, con cifras ASCII. Se escribe a mano porque dar formato con
 * el idioma del teléfono podría cambiar las cifras.
 */
internal fun ByteArray.toLowerHex(): String {
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xff
        out.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0f])
    }
    return out.toString()
}

/** Un acumulador de SHA-256 nuevo. */
internal fun newSha256(): MessageDigest = MessageDigest.getInstance("SHA-256")
