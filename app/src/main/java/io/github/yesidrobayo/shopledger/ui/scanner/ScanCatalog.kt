package io.github.yesidrobayo.shopledger.ui.scanner

import io.github.yesidrobayo.shopledger.domain.model.catalog.CodeMatch
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.AssignBarcodeResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ReactivateProductResult

/**
 * Lo que el escáner necesita del catálogo para resolver un código en la pantalla misma: buscarlo,
 * darle el código a un producto que no tiene y reactivar un archivado. Sin él, como al llenar el
 * código de un producto, el escáner devuelve el texto tal cual.
 */
interface ScanCatalog {
    suspend fun find(code: String): CodeMatch

    /** Los productos sin archivar que no tienen código, para asignarles el leído. */
    suspend fun withoutCode(): List<ScanCandidate>

    suspend fun assign(productId: Long, code: String): AssignBarcodeResult

    suspend fun reactivate(productId: Long): ReactivateProductResult
}

/** Un producto que puede recibir el código leído, con su nombre normalizado para buscarlo. */
data class ScanCandidate(val id: Long, val name: String, val normalizedName: String)

/** Un código tomado: el texto y, si el escáner lo buscó, el producto que encontró. */
data class ScanTake(val code: String, val match: CodeMatch.Found?)
