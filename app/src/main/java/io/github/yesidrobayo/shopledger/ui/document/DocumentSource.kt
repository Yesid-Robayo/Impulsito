package io.github.yesidrobayo.shopledger.ui.document

import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.settings.ReceiptSettings
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.navigation.ScreenDependencies
import kotlinx.coroutines.flow.first

/**
 * Lo que un documento para el cliente toma del negocio al armarse: la plantilla del comprobante,
 * el nombre que lo encabeza, la imagen del logo, las unidades por id y la zona, para la hora. Sin
 * zona conocida, el documento lleva solo la fecha; sin logo, sale igual.
 */
internal class DocumentSource(
    val template: ReceiptSettings,
    val businessName: String?,
    val logo: StoredImage?,
    val units: Map<Long, MeasureUnit>,
    val zone: BusinessTimeZone?,
) {
    companion object {
        /** Lo lee de la base abierta, de una vez. */
        suspend fun read(dependencies: ScreenDependencies): DocumentSource {
            val ports = dependencies.ports
            val settings = ports.transactions.read { ports.settings.get() }
            val calendar = BusinessCalendar.of(settings.regional)
            return DocumentSource(
                template = settings.receipt,
                businessName = settings.profile.businessName,
                logo = settings.receipt.logoMediaId?.let { id ->
                    ports.transactions.read { ports.mediaAssets.findImage(id) }
                },
                units = ports.units.observeAll().first().associateBy { it.id },
                zone = (calendar as? BusinessCalendarResult.Ready)?.calendar?.zone,
            )
        }
    }
}
