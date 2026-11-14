package io.github.yesidrobayo.shopledger.domain.usecase.settings

import io.github.yesidrobayo.shopledger.domain.model.catalog.ImageImport
import io.github.yesidrobayo.shopledger.domain.model.catalog.MediaKind
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.settings.ReceiptLogoChange
import io.github.yesidrobayo.shopledger.domain.model.settings.ReceiptSettings
import io.github.yesidrobayo.shopledger.domain.model.settings.ReceiptTemplateField
import io.github.yesidrobayo.shopledger.domain.model.settings.ReceiptTemplateInput
import io.github.yesidrobayo.shopledger.domain.port.ImageImporter
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeMediaAssetRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTransactionRunner
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * La plantilla del comprobante: textos recortados y hasta 300 caracteres, el logo anotado como
 * imagen del negocio en la misma transacción, el anterior marcado para borrarse si nadie lo usa, y
 * nada escrito si no cambió nada. La dirección del portal no se toca.
 */
class ReceiptTemplateUseCasesTest {
    private val now = Instant.fromEpochMilliseconds(1_700_000_600_000L)
    private val database = InMemoryDatabase()
    private val transactions = FakeTransactionRunner(database)
    private val media = FakeMediaAssetRepository(database)
    private val settings = FakeAppSettingsRepository(
        appSettings(regionalSettings("UTC", DayOfWeek.MONDAY)).let {
            it.copy(receipt = it.receipt.copy(fiscalPortalUrl = PORTAL))
        },
    )
    private val save = SaveReceiptTemplateUseCase(transactions, settings, media, FixedClock(now))

    @Test
    fun `texts are trimmed, a blank one is none, and the portal stays`() = runTest {
        val result = save(input(details = "  Calle Mayor 8, local 2 \n", footer = "   "))

        assertEquals(SaveReceiptTemplateResult.Saved, result)
        assertEquals(
            ReceiptSettings(null, "Calle Mayor 8, local 2", null, PORTAL),
            settings.settings.value.receipt,
        )
        assertEquals(now, settings.settings.value.updatedAt)
        assertEquals(1, transactions.commits)
    }

    @Test
    fun `a new logo is recorded as the business image in the same transaction`() = runTest {
        save(input(logo = ReceiptLogoChange.Use(image('a'))))

        val id = requireNotNull(settings.settings.value.receipt.logoMediaId)
        val row = requireNotNull(database.tables.value.mediaAssets[id])
        assertEquals(image('a'), row.image)
        assertEquals(MediaKind.BUSINESS_LOGO, row.kind)
        assertNull(row.orphanedAt)
        assertEquals(1, transactions.commits)
    }

    @Test
    fun `replacing or removing the logo marks the old one unless something else uses it`() =
        runTest {
            save(input(logo = ReceiptLogoChange.Use(image('a'))))
            save(input(logo = ReceiptLogoChange.Use(image('b'))))

            assertEquals(now, database.tables.value.mediaAssets.getValue(1L).orphanedAt)
            media.otherReferences += 2L
            save(input(logo = ReceiptLogoChange.Remove))

            assertNull(settings.settings.value.receipt.logoMediaId)
            assertNull(database.tables.value.mediaAssets.getValue(2L).orphanedAt)
            assertEquals(3, transactions.commits)
        }

    @Test
    fun `keeping the logo and the same texts writes nothing`() = runTest {
        save(input(details = "Calle 8", logo = ReceiptLogoChange.Use(image('a'))))

        val result = save(input(details = " Calle 8 ", logo = ReceiptLogoChange.Keep))

        assertEquals(SaveReceiptTemplateResult.Saved, result)
        assertEquals(1, settings.receiptTemplateWrites.size)
        assertEquals(1, transactions.commits)
        assertEquals(1, transactions.rollbacks)
    }

    @Test
    fun `texts over 300 characters are rejected, counting what the database counts`() = runTest {
        val emoji = "😀"
        val fits = save(input(details = emoji.repeat(300), footer = "a".repeat(300)))
        val tooLong = save(input(details = "a".repeat(301), footer = emoji.repeat(301)))

        assertEquals(SaveReceiptTemplateResult.Saved, fits)
        assertEquals(
            SaveReceiptTemplateResult.TooLong(
                setOf(ReceiptTemplateField.BUSINESS_DETAILS, ReceiptTemplateField.FOOTER),
            ),
            tooLong,
        )
        assertEquals(1, settings.receiptTemplateWrites.size)
    }

    @Test
    fun `a failed write changes nothing and says why`() = runTest {
        transactions.restoreInProgress = true

        val result = save(input(details = "Calle 8"))

        assertEquals(SaveReceiptTemplateResult.Failed(StorageFailure.RESTORE_IN_PROGRESS), result)
        assertEquals(emptyList<ReceiptSettings>(), settings.receiptTemplateWrites)
    }

    @Test
    fun `the logo comes in as a business image`() = runTest {
        val kinds = mutableListOf<MediaKind>()
        val importer = ImageImporter { _, kind ->
            kinds += kind
            ImageImport.Unreadable
        }

        assertEquals(ImageImport.Unreadable, ImportReceiptLogoUseCase(importer)("content://logo"))
        assertEquals(listOf(MediaKind.BUSINESS_LOGO), kinds)
    }

    private fun input(
        details: String = "",
        footer: String = "",
        logo: ReceiptLogoChange = ReceiptLogoChange.Keep,
    ) = ReceiptTemplateInput(logo, details, footer)

    private fun image(digit: Char) = StoredImage(
        sha256 = digit.toString().repeat(64),
        mimeType = "image/webp",
        widthPx = 640,
        heightPx = 320,
        sizeBytes = 20_000,
    )

    private companion object {
        const val PORTAL = "https://example.com/portal"
    }
}
