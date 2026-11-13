package io.github.yesidrobayo.shopledger.ui.product

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceAdjustment
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceContext
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceRequest
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceScope
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkUndoPlan
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkUndoRevert
import io.github.yesidrobayo.shopledger.domain.model.pricing.FxRate
import io.github.yesidrobayo.shopledger.domain.model.pricing.NewFxRate
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.ReferenceCurrency
import io.github.yesidrobayo.shopledger.domain.rule.catalog.BulkPricePreviewer
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ReferenceScale
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate

/**
 * Estados inventados de la actualización masiva para las vistas previas y la galería: un producto
 * que queda bajo su costo, uno que el redondeo aleja de lo pedido, uno sin precio y nombres largos.
 */
object BulkPriceSamples {
    private val products = listOf(
        ProductDetailSamples.product(price = 12_350L, cost = 8_000L).copy(id = 1L),
        ProductDetailSamples.product(price = 10_000L, cost = 9_500L).copy(id = 2L),
        ProductDetailSamples.product(price = 80L, cost = 50L).copy(id = 3L),
        ProductDetailSamples.product(price = 0L).copy(id = 4L),
    )

    /** Con −10 % y paso de 100: un aviso de pérdida, uno de redondeo y uno excluido. */
    fun ready(percentText: String = "-10", percent: Long = -1_000L): BulkPriceUiState {
        val request = BulkPriceRequest(BulkPriceScope.All, BasisPoints(percent), Money(100L))
        return BulkPriceUiState(
            input = BulkPriceInput(percentText = percentText, rounding = BulkRounding.SAVED_STEP),
            content = BulkPreviewContent.Ready(BulkPricePreviewer.preview(products, request)),
            savedStep = Money(100L),
            percent = BasisPoints(percent),
        )
    }

    /**
     * Con la última actualización por deshacer y el diálogo abierto: dos precios vuelven y uno se
     * cambió después.
     */
    fun confirmingUndo(): BulkPriceUiState = needsInput.copy(
        undo = BulkUndoPlan(
            batchId = Uuid.parse("00000000-0000-4000-8000-000000000001"),
            reverts = listOf(
                BulkUndoRevert(1L, from = Money(13_400L), to = Money(12_350L)),
                BulkUndoRevert(3L, from = Money(100L), to = Money(80L)),
            ),
            kept = 1,
        ),
        confirmingUndo = true,
    )

    /** Monto de +250 sobre la lista general y una fija: cada fila dice su lista. */
    fun lists(): BulkPriceUiState {
        val general = PriceList(1L, "DEFAULT", null, PriceListPricing.Fixed, null)
        val wholesale = PriceList(2L, null, "Mayoristas", PriceListPricing.Fixed, null)
        val choices = BulkListChoices(
            listOf(general, wholesale),
            mapOf(1L to mapOf(2L to Money(11_000L))),
        )
        val request = BulkPriceRequest(
            BulkPriceScope.All,
            BulkPriceAdjustment.Amount(Money(250L)),
            Money.ZERO,
            priceListIds = setOf(1L, 2L),
        )
        return BulkPriceUiState(
            input = BulkPriceInput(
                mode = BulkPriceMode.AMOUNT,
                amountText = "250",
                listIds = setOf(1L, 2L),
            ),
            content = BulkPreviewContent.Ready(
                BulkPricePreviewer.preview(products, request, choices.context),
            ),
            savedStep = Money(100L),
            lists = choices.writable,
        )
    }

    /**
     * `Por cotización`: 12,50 de referencia por 4.150,5 da 51.881, y los que no tienen precio de
     * referencia quedan fuera. Sin [withRate], todavía no hay cotización y se pide registrarla.
     */
    fun quote(withRate: Boolean = true): BulkPriceUiState {
        val reference = ReferenceCurrency(requireNotNull(CurrencyCode.of("XTS")), 2)
        val rate = FxRate(
            1L,
            NewFxRate(
                reference.code,
                4_150_500_000L,
                null,
                Instant.fromEpochMilliseconds(0L),
                BusinessDate(LocalDate(2026, 11, 1)),
                Instant.fromEpochMilliseconds(0L),
            ),
        )
        val referenced = products.mapIndexed { index, product ->
            if (index != 0) {
                product
            } else {
                product.copy(details = product.details.copy(referencePrice = Money(1_250L)))
            }
        }
        val request = BulkPriceRequest(
            BulkPriceScope.All,
            BulkPriceAdjustment.ReferenceRate(rate.content.rateMicro),
            Money.ZERO,
        )
        val context = BulkPriceContext(scale = ReferenceScale(0, reference.decimals))
        return BulkPriceUiState(
            input = BulkPriceInput(mode = BulkPriceMode.REFERENCE_RATE),
            content =
                if (withRate) {
                    BulkPreviewContent.Ready(
                        BulkPricePreviewer.preview(referenced, request, context),
                    )
                } else {
                    BulkPreviewContent.NeedsInput
                },
            savedStep = Money(100L),
            reference = reference,
            rate = rate.takeIf { withRate },
        )
    }

    /** Sin porcentaje todavía. */
    val needsInput: BulkPriceUiState =
        BulkPriceUiState(content = BulkPreviewContent.NeedsInput, savedStep = Money(100L))
}
