package io.github.yesidrobayo.shopledger.ui.scanner

import zxingcpp.BarcodeReader

/**
 * Los formatos que busca el escáner. Cada formato de más cuesta tiempo en cada cuadro, así que el
 * lector solo busca los de un comercio: los de producto (EAN y UPC), Code 128, que es el de los
 * códigos propios de la app y el de muchas etiquetas de bodega, y QR.
 */
enum class ScanFormat(internal val native: BarcodeReader.Format) {
    EAN_13(BarcodeReader.Format.EAN_13),
    EAN_8(BarcodeReader.Format.EAN_8),
    UPC_A(BarcodeReader.Format.UPC_A),
    UPC_E(BarcodeReader.Format.UPC_E),
    CODE_128(BarcodeReader.Format.CODE_128),
    QR_CODE(BarcodeReader.Format.QR_CODE),
    ;

    companion object {
        /** El formato de la app para uno de zxing-cpp, o `null` si no es de los que se buscan. */
        fun fromNative(format: BarcodeReader.Format): ScanFormat? =
            entries.firstOrNull { it.native == format }
    }
}

/**
 * Las opciones de zxing-cpp del escáner: el perfil insistente, que gira el cuadro, prueba más
 * líneas y lo reduce si es grande. Así lee códigos torcidos, lejanos o algo desenfocados, a cambio
 * de unos milisegundos por cuadro que la cámara no echa de menos. Un solo código por cuadro: el
 * escáner nunca necesita más, y buscar otro solo gasta tiempo.
 */
internal fun scannerReaderOptions(): BarcodeReader.Options = BarcodeReader.Options(
    formats = ScanFormat.entries.map { it.native }.toSet(),
    tryHarder = true,
    tryRotate = true,
    tryInvert = false,
    tryDownscale = true,
    maxNumberOfSymbols = 1,
)
