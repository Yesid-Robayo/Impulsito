package io.github.yesidrobayo.shopledger.domain.usecase.settings

import io.github.yesidrobayo.shopledger.domain.model.catalog.ImageImport
import io.github.yesidrobayo.shopledger.domain.model.catalog.MediaKind
import io.github.yesidrobayo.shopledger.domain.model.settings.ReceiptLogoChange
import io.github.yesidrobayo.shopledger.domain.model.settings.ReceiptTemplateField
import io.github.yesidrobayo.shopledger.domain.model.settings.ReceiptTemplateInput
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.ImageImporter
import io.github.yesidrobayo.shopledger.domain.port.MediaAssetRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.recordImage
import io.github.yesidrobayo.shopledger.domain.usecase.payment.releaseImage

/**
 * Trae el logo que eligió la persona, recodificado a 640 px de lado mayor y 64 KiB como máximo, y
 * sin metadatos. Todavía no lo asigna: eso pasa al guardar la plantilla, en su transacción. Si la
 * persona no guarda, el archivo queda sin fila y la limpieza de fotos lo borra.
 */
class ImportReceiptLogoUseCase(private val importer: ImageImporter) {
    suspend operator fun invoke(source: String): ImageImport =
        importer.import(source, MediaKind.BUSINESS_LOGO)
}

/** Resultado de [SaveReceiptTemplateUseCase]. */
sealed interface SaveReceiptTemplateResult {
    /** Quedó así, o ya estaba así y no hacía falta escribir. */
    data object Saved : SaveReceiptTemplateResult

    /** Estos textos pasan de [MAX_RECEIPT_TEXT_LENGTH] caracteres. No cambió nada. */
    data class TooLong(val fields: Set<ReceiptTemplateField>) : SaveReceiptTemplateResult

    /** La base no pudo escribir, por [failure]. No cambió nada. */
    data class Failed(val failure: StorageFailure) : SaveReceiptTemplateResult
}

/** Lo más largo que puede ser cada texto de la plantilla, en caracteres. */
const val MAX_RECEIPT_TEXT_LENGTH = 300

/**
 * Guarda la plantilla del comprobante: logo, datos del negocio y pie. No toca la dirección del
 * portal de trámites, que se guarda en su propia pantalla.
 *
 * Es un dato del negocio y viaja en la copia: va en una transacción y cuenta como cambio sin
 * respaldar. Si queda igual que antes, no escribe ni cuenta un cambio. Un logo nuevo se anota como
 * imagen del negocio; el que deja de usarse se marca para que la limpieza lo borre, salvo que otro
 * dato lo use todavía.
 */
class SaveReceiptTemplateUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val media: MediaAssetRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(input: ReceiptTemplateInput): SaveReceiptTemplateResult {
        val details = input.businessDetails.trim().takeIf { it.isNotEmpty() }
        val footer = input.footerText.trim().takeIf { it.isNotEmpty() }
        val tooLong = buildSet {
            if (details.isTooLong()) add(ReceiptTemplateField.BUSINESS_DETAILS)
            if (footer.isTooLong()) add(ReceiptTemplateField.FOOTER)
        }
        if (tooLong.isNotEmpty()) return SaveReceiptTemplateResult.TooLong(tooLong)
        return try {
            transactions.write {
                val now = clock.now()
                val current = settings.get().receipt
                val logo = when (val change = input.logo) {
                    ReceiptLogoChange.Keep -> current.logoMediaId

                    ReceiptLogoChange.Remove -> null

                    is ReceiptLogoChange.Use ->
                        recordImage(media, change.image, MediaKind.BUSINESS_LOGO, now)
                }
                val unchanged = logo == current.logoMediaId &&
                    details == current.businessDetails &&
                    footer == current.footerText
                if (unchanged) {
                    TxResult.Rollback(SaveReceiptTemplateResult.Saved)
                } else {
                    settings.updateReceiptTemplate(logo, details, footer, now)
                    if (logo != current.logoMediaId) releaseImage(media, current.logoMediaId, now)
                    TxResult.Commit(SaveReceiptTemplateResult.Saved)
                }
            }
        } catch (failure: StorageFailureException) {
            SaveReceiptTemplateResult.Failed(failure.failure)
        }
    }

    /**
     * Si pasa del largo que acepta la base, contado como ella: un emoji o una letra fuera del plano
     * básico es un carácter, aunque ocupe dos unidades de `String`.
     */
    private fun String?.isTooLong(): Boolean {
        val text = this ?: return false
        var count = 0
        var index = 0
        while (index < text.length) {
            val pair = text[index].isHighSurrogate() &&
                index + 1 < text.length &&
                text[index + 1].isLowSurrogate()
            index += if (pair) 2 else 1
            count++
        }
        return count > MAX_RECEIPT_TEXT_LENGTH
    }
}
