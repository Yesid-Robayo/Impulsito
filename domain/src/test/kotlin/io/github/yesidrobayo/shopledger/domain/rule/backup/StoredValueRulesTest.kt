package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateCheck
import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateFinding
import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateVerdict
import io.github.yesidrobayo.shopledger.domain.model.backup.FindingSeverity
import io.github.yesidrobayo.shopledger.domain.model.backup.NewCopyMarks
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import java.time.ZoneId
import java.util.Locale
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El validador lee los valores guardados sin comparar nada con listas de países, monedas o zonas.
 * Un tipo de método de pago desconocido es un aviso, y un tipo del libro desconocido es daño.
 */
class StoredValueRulesTest {
    @Test
    fun `an unknown payment method kind is read as other and is a warning, not a rejection`() {
        for (stored in listOf("VOUCHER", "QR_PAYMENT", "cash", "")) {
            val reading = StoredValueRules.readPaymentMethodKind(stored)
            val finding = CandidateFinding(CandidateCheck.OPEN_DATA, cases = 1)

            assertEquals(StoredReading.Substituted(PaymentMethodKind.OTHER, finding), reading)
            assertEquals(PaymentMethodKind.OTHER, reading.valueOrNull)
            assertEquals(FindingSeverity.WARNING, reading.findingOrNull?.severity)

            // La base sigue restaurable, y una copia nueva con ese valor cuenta como válida.
            val verdict = CandidateVerdict.of(listOfNotNull(reading.findingOrNull))
            assertEquals(CandidateVerdict.Restorable(listOf(finding)), verdict)
            assertEquals(NewCopyMarks(valid = true, fatal = false), verdict.newCopyMarks)
        }
    }

    @Test
    fun `a known payment method kind is read with no finding`() {
        for (kind in PaymentMethodKind.entries) {
            val reading = StoredValueRules.readPaymentMethodKind(kind.name)

            assertEquals(StoredReading.Recognized(kind), reading)
            assertEquals(kind, reading.valueOrNull)
            assertNull(reading.findingOrNull)
        }
    }

    @Test
    fun `an unknown ledger type is damage and rejects the copy`() {
        // El mismo caso que un método de pago desconocido, pero en el libro: con la misma versión
        // de esquema, un tipo que no existe es daño.
        val reading = StoredValueRules.readLedgerValue("SALE_REFUND", StockMovementType.entries)

        val damage = CandidateFinding(CandidateCheck.LEDGER_ENUMS, cases = 1)
        assertEquals(StoredReading.Damaged(damage), reading)
        assertNull(reading.valueOrNull)
        assertEquals(FindingSeverity.FATAL, reading.findingOrNull?.severity)
        val verdict = CandidateVerdict.of(listOfNotNull(reading.findingOrNull))
        assertEquals(NewCopyMarks(valid = false, fatal = true), verdict.newCopyMarks)
    }

    @Test
    fun `a ledger type is read by its exact name`() {
        assertEquals(
            StoredReading.Recognized(StockMovementType.SALE),
            StoredValueRules.readLedgerValue("SALE", StockMovementType.entries),
        )
        val lowercase = StoredValueRules.readLedgerValue("sale", StockMovementType.entries)
        assertTrue(lowercase is StoredReading.Damaged)
    }

    @Test
    fun `any three uppercase letters have the shape of a currency code`() {
        // Toda combinación, exista o no como moneda: el validador no conoce ninguna lista.
        for (first in 'A'..'Z') {
            for (second in 'A'..'Z') {
                for (third in 'A'..'Z') {
                    assertTrue(RegionalShape.CURRENCY_CODE.matches("$first$second$third"))
                }
            }
        }
    }

    @Test
    fun `other texts do not have the shape of a currency code`() {
        for (text in listOf("", "US", "USDX", "usd", "Usd", "978", "ÇÇÇ", "U\$D", " EU")) {
            assertFalse(text, RegionalShape.CURRENCY_CODE.matches(text))
        }
    }

    @Test
    fun `every language tag the JVM knows has the shape of a language tag`() {
        val tags = Locale.getAvailableLocales().map { it.toLanguageTag() }

        val rejected = tags.filterNot(RegionalShape.LANGUAGE_TAG::matches)

        assertTrue(rejected.toString(), rejected.isEmpty())
    }

    @Test
    fun `unknown but well formed language tags pass, malformed ones do not`() {
        for (tag in listOf("zz", "zz-ZZ", "qaa-Qaaa-QM", "es-419", "abcdefgh-12345678")) {
            assertTrue(tag, RegionalShape.LANGUAGE_TAG.matches(tag))
        }
        val malformed =
            listOf(
                "", "e", "1e", "abcdefghi", "es_CO", "es--CO", "-es", "es-", "es-ABCDEFGHI",
                "español", "es CO", "x-private",
            )
        for (tag in malformed) assertFalse(tag, RegionalShape.LANGUAGE_TAG.matches(tag))
    }

    @Test
    fun `every zone of the zone database has the shape of a zone id`() {
        val zones = TimeZone.availableZoneIds + ZoneId.getAvailableZoneIds()

        val rejected = zones.filterNot(RegionalShape.TIME_ZONE_ID::matches)

        assertTrue(rejected.toString(), rejected.isEmpty())
    }

    @Test
    fun `unknown but well formed zone ids pass, malformed ones do not`() {
        val wellFormed =
            listOf("Mars/Olympus_Mons", "UTC", "Etc/GMT+5", "GMT+05:00", "+05:00", "A.B")
        for (zone in wellFormed) {
            assertTrue(zone, RegionalShape.TIME_ZONE_ID.matches(zone))
        }
        val malformed =
            listOf(
                "", "/", "Area//City", "Area/", "/City", "../etc", "Area/./City", "-City",
                "Area/-City", "Área/Ciudad", "Area/Big City", "Area\\City", "Area/City#1",
            )
        for (zone in malformed) assertFalse(zone, RegionalShape.TIME_ZONE_ID.matches(zone))
    }

    @Test
    fun `a malformed regional value is a warning, a well formed one has no finding`() {
        val warning = CandidateFinding(CandidateCheck.OPEN_DATA, cases = 1)

        assertNull(StoredValueRules.checkRegionalShape(RegionalShape.CURRENCY_CODE, "QQQ"))
        assertNull(StoredValueRules.checkRegionalShape(RegionalShape.TIME_ZONE_ID, "Mars/Base"))
        assertEquals(warning, StoredValueRules.checkRegionalShape(RegionalShape.CURRENCY_CODE, "q"))
        assertEquals(warning, StoredValueRules.checkRegionalShape(RegionalShape.LANGUAGE_TAG, "_"))
        assertEquals(FindingSeverity.WARNING, warning.severity)
    }
}
