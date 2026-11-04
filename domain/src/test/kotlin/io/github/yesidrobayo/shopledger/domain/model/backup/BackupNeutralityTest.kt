package io.github.yesidrobayo.shopledger.domain.model.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los metadatos y la cabecera de una copia no llevan moneda, región ni zona, y una clave nueva que
 * los lleve hace fallar esta prueba.
 *
 * Tres capas, de la más fácil de esquivar a la más difícil. La lista revisada obliga a que toda
 * clave nueva pase por aquí. El vocabulario cerrado rechaza cualquier palabra que nadie revisó, sea
 * un sinónimo o una abreviatura. Y la revisión de valores prueba cada forma con los identificadores
 * reales de monedas, países, idiomas y zonas. Cada capa tiene su control negativo: una clave
 * regional inventada que la capa debe atrapar.
 */
class BackupNeutralityTest {
    @Test
    fun `the closed list of metadata keys is exactly the reviewed one`() {
        // Una clave nueva hace fallar esta prueba hasta que se agrega aquí. Antes de agregarla,
        // revisa que no lleve moneda, región ni zona: esos datos viven dentro de la base.
        val reviewed =
            mapOf(
                "sl_kind" to MetaValueForm.OneOf(listOf("backup", "media")),
                "sl_format" to MetaValueForm.OneOf(listOf("1", "2")),
                "sl_key_id" to MetaValueForm.LowerHex(16),
                "sl_media_sha256" to MetaValueForm.LowerHex(64),
                "sl_device_id" to MetaValueForm.Uuid,
                "sl_device_label" to MetaValueForm.DeviceLabel(124),
                "sl_generation" to MetaValueForm.Count,
                "sl_schema_version" to MetaValueForm.Count,
                "sl_app_version" to MetaValueForm.AppVersion,
                "sl_sha256" to MetaValueForm.LowerHex(64),
                "sl_raw_size" to MetaValueForm.Count,
                "sl_file_size" to MetaValueForm.Count,
                "sl_records" to MetaValueForm.Count,
                "sl_catalog_count" to MetaValueForm.Count,
                "sl_valid" to MetaValueForm.OneOf(listOf("true", "false")),
                "sl_fatal" to MetaValueForm.OneOf(listOf("1")),
                "sl_pinned" to MetaValueForm.OneOf(listOf("1")),
            )

        assertEquals(reviewed, BackupMetaKey.entries.associate { it.wireName to it.form })
    }

    @Test
    fun `every metadata key name is built only from neutral words`() {
        val problems = BackupMetaKey.entries.associate { it.wireName to nameProblems(it.wireName) }
            .filterValues { it.isNotEmpty() }

        assertTrue(problems.toString(), problems.isEmpty())
    }

    @Test
    fun `every header field name is built only from neutral words`() {
        val problems =
            BackupHeaderField.entries.associate { it.wireName to nameProblems(it.wireName) }
                .filterValues { it.isNotEmpty() }

        assertTrue(problems.toString(), problems.isEmpty())
    }

    @Test
    fun `no metadata value form accepts a currency a region or a zone`() {
        val accepted =
            BackupMetaKey.entries.filter { it.form !is MetaValueForm.DeviceLabel }
                .associate { it.wireName to acceptedRegionalValues(it.form).take(5) }
                .filterValues { it.isNotEmpty() }

        assertTrue(accepted.toString(), accepted.isEmpty())
    }

    @Test
    fun `the regional corpus really holds currencies regions languages and zones`() {
        // Sin esto, un corpus vacío por un cambio de la JVM haría pasar la prueba anterior.
        val samples =
            listOf("COP", "eur", "BR", "es-AR", "es_MX", "America/Bogota", "+05:00", "UTC", "€")

        assertTrue(RegionalCorpus.values.containsAll(samples))
        assertTrue(RegionalCorpus.values.size > 40_000)
    }

    @Test
    fun `the device label is the only free text and it names the phone`() {
        // Es la única clave que acepta texto libre. Lo escribe el sistema del teléfono con su
        // fabricante y su modelo; no lo escribe la persona ni sale de los datos del negocio.
        val freeText = BackupMetaKey.entries.filter { it.form is MetaValueForm.DeviceLabel }

        assertEquals(listOf(BackupMetaKey.DEVICE_LABEL), freeText)
    }

    @Test
    fun `the header holds only numbers and opaque bytes and its only instant is UTC`() {
        val kinds = BackupHeaderField.entries.map { it.kind }.toSet()
        val instants = BackupHeaderField.entries.filter {
            it.kind == HeaderValueKind.UTC_EPOCH_MILLIS
        }

        assertEquals(HeaderValueKind.entries.toSet(), kinds)
        assertEquals(listOf(BackupHeaderField.CREATED_AT_UTC_MS), instants)
    }

    @Test
    fun `a key named after a currency a region or a zone fails the name review`() {
        // Ninguna de estas palabras está en el vocabulario, tampoco las abreviaturas.
        val regionalNames =
            listOf(
                "sl_currency", "sl_currency_code", "sl_cur", "sl_ccy", "sl_decimals", "sl_region",
                "sl_country", "sl_locale", "sl_lang", "sl_tz", "sl_time_zone", "sl_offset_minutes",
                "sl_business_day", "sl_local_time",
            )

        for (name in regionalNames) assertFalse(name, nameProblems(name).isEmpty())
    }

    @Test
    fun `a name that is not lowercase ASCII snake case fails the name review`() {
        for (name in listOf("SL_KIND", "sl-kind", "sl__kind", "sl_kínd", "_sl_kind")) {
            assertFalse(name, nameProblems(name).isEmpty())
        }
    }

    @Test
    fun `a key whose values could carry a currency a region or a zone fails the value review`() {
        // Formas que alguien podría escribir para una clave regional: un literal con una moneda, un
        // hexadecimal tan corto que forma códigos, una lista de zonas, o texto libre.
        val regionalForms =
            listOf(
                MetaValueForm.OneOf(listOf("COP")),
                MetaValueForm.OneOf(listOf("backup", "es-CO")),
                MetaValueForm.OneOf(listOf("America/Bogota")),
                MetaValueForm.LowerHex(3),
                MetaValueForm.DeviceLabel(64),
            )

        for (form in regionalForms) {
            assertFalse(form.toString(), acceptedRegionalValues(form).isEmpty())
        }
    }

    @Test
    fun `wire names are unique and found only by their exact text`() {
        val names = BackupMetaKey.entries.map { it.wireName }

        assertEquals(names.size, names.toSet().size)
        for (key in BackupMetaKey.entries) {
            assertEquals(key, BackupMetaKey.fromWireName(key.wireName))
        }
        for (name in listOf("SL_KIND", "sl_kind ", "kind", "sl_currency", "")) {
            assertEquals(null, BackupMetaKey.fromWireName(name))
        }
    }

    private fun nameProblems(name: String) = NeutralityReview.nameProblems(name)

    private fun acceptedRegionalValues(form: MetaValueForm) =
        NeutralityReview.acceptedRegionalValues(form)
}
