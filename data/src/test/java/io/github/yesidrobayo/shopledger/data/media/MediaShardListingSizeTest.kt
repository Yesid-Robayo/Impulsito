package io.github.yesidrobayo.shopledger.data.media

import io.github.yesidrobayo.shopledger.data.destination.WebDavXmlReader
import java.security.MessageDigest
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El peso de la lista de fotos frente al tope del XML. Cada archivo se cuenta con una entrada de
 * `PROPFIND` como las de Koofr: con 1.000 fotos y sus `.slmeta` en una carpeta plana, Koofr
 * respondió 1.045.609 bytes, así que una carpeta plana no pasa de unas 8.000 fotos. Con una
 * subcarpeta por prefijo, cada lista de 3.000 fotos inventadas queda muy por debajo del tope.
 */
class MediaShardListingSizeTest {
    @Test
    fun theEntryUsedHereWeighsLikeKoofrsOrMore() {
        val flat = listingBytes(sums(1_000), shard = null)

        // Ni muy lejos de lo medido, ni por debajo de un 80 %: la prueba no puede ser optimista.
        assertTrue("$flat", flat in KOOFR_FLAT_1000 * 8 / 10..KOOFR_FLAT_1000 * 5 / 4)
    }

    @Test
    fun aFlatFolderWouldPassTheCapWithTooManyPhotos() {
        val flat = listingBytes(sums(9_000), shard = null)

        assertTrue("$flat", flat > WebDavXmlReader.MAX_XML_BYTES)
    }

    @Test
    fun withSubfoldersEveryListingOfThreeThousandPhotosStaysFarBelowTheCap() {
        val sums = sums(3_000)
        val byShard = sums.groupBy(MediaNames::shardOf)

        assertEquals(sums.size, byShard.values.sumOf { it.size })
        assertTrue(byShard.keys.all(MediaNames::isShard))
        val largest = byShard.map { (shard, inShard) -> listingBytes(inShard, shard) }.max()
        // Cada lista pesa menos de un 1 % del tope: unas 256 veces menos que la carpeta plana.
        assertTrue("$largest", largest < WebDavXmlReader.MAX_XML_BYTES / 100)
        assertTrue("$largest", largest * 50 < listingBytes(sums, shard = null))
    }

    /** [count] sumas SHA-256 inventadas, siempre las mismas. */
    private fun sums(count: Int): List<String> {
        val random = Random(SEED)
        val digest = MessageDigest.getInstance("SHA-256")
        return List(count) {
            digest.digest(random.nextBytes(32)).joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * Los bytes de la respuesta a `PROPFIND` con `Depth: 1` de la carpeta con las fotos [sums],
     * cada una con su `.slmeta`: en `media/` si [shard] es nulo, o en `media/<shard>/`.
     */
    private fun listingBytes(sums: List<String>, shard: String?): Long {
        val folder = FOLDER + (shard?.let { "$it/" } ?: "")
        val body = StringBuilder(HEAD).append(entry(folder, "", collection = true))
        for (sha in sums) {
            val photo = MediaNames.photo(sha)
            body.append(entry(folder, photo, collection = false))
            body.append(entry(folder, MediaNames.metaOf(photo), collection = false))
        }
        body.append(TAIL)
        return body.toString().toByteArray(Charsets.UTF_8).size.toLong()
    }

    private fun entry(folder: String, name: String, collection: Boolean): String {
        val type = if (collection) FOLDER_TYPE else FILE_TYPE
        return "<D:response><D:href>$folder$name</D:href><D:propstat><D:prop>" +
            "<D:displayname>$name</D:displayname><D:getcontentlength>67400</D:getcontentlength>" +
            "<D:getlastmodified>Sat, 07 Nov 2026 03:43:00 GMT</D:getlastmodified>" +
            "<D:getetag>\"0123456789abcdef0123456789abcdef\"</D:getetag>$type</D:prop>" +
            "<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>\n"
    }

    private companion object {
        /** Lo que pesó en Koofr la lista plana de 1.000 fotos con sus `.slmeta`. */
        const val KOOFR_FLAT_1000 = 1_045_609L
        const val SEED = 7
        const val FOLDER = "/dav/Koofr/Tienda/media/"
        const val FOLDER_TYPE = "<D:resourcetype><D:collection/></D:resourcetype>"
        const val FILE_TYPE = "<D:getcontenttype>image/webp</D:getcontenttype><D:resourcetype/>"
        const val HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<D:multistatus xmlns:D=\"DAV:\">\n"
        const val TAIL = "</D:multistatus>\n"
    }
}
