package io.github.yesidrobayo.shopledger.ui.currency

import java.io.File

/**
 * Los 57 puntos de código de la categoría Unicode `Sc` en el plano básico, según Unicode 15.1.
 * Van escritos y no calculados, porque Android antiguo trae datos de Unicode viejos y no
 * reconocería `U+20BF` ni `U+20C0`.
 */
val CURRENCY_SIGNS_BMP: List<Int> =
    listOf(0x0024, 0x00A2, 0x00A3, 0x00A4, 0x00A5, 0x058F, 0x060B, 0x07FE, 0x07FF) +
        listOf(0x09F2, 0x09F3, 0x09FB, 0x0AF1, 0x0BF9, 0x0E3F, 0x17DB) +
        (0x20A0..0x20C0).toList() +
        listOf(0xA838, 0xFDFC, 0xFE69, 0xFF04, 0xFFE0, 0xFFE1, 0xFFE5, 0xFFE6)

/** El código de ISO 4217 reservado para pruebas: no es la moneda de ningún lugar. */
const val TEST_CODE = "XTS"

/** Los puntos de código de una fuente del APK, leídos desde el módulo, el directorio de trabajo. */
fun fontCodePoints(name: String): Set<Int> =
    CmapReader.readCodePoints(File("src/main/res/font/$name.ttf").readBytes())

fun label(codePoint: Int): String =
    "U+" + Integer.toHexString(codePoint).uppercase().padStart(4, '0')
