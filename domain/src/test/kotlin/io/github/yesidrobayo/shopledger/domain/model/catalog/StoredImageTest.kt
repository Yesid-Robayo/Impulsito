package io.github.yesidrobayo.shopledger.domain.model.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Una imagen guardada se llama como su contenido, y su suma es la que espera la base. */
class StoredImageTest {
    private val sha = "0123456789abcdef".repeat(4)

    @Test
    fun `the file is named after its content`() {
        val image = StoredImage(sha, "image/webp", 640, 480, 20_000)

        assertEquals("$sha.webp", image.fileName)
        assertEquals("media", StoredImage.FOLDER)
    }

    @Test
    fun `the sum is 64 lowercase hexadecimal characters`() {
        for (wrong in listOf(
            sha.dropLast(1),
            sha + "0",
            sha.uppercase(),
            sha.dropLast(1) + "g",
            sha.dropLast(1) + "-",
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                StoredImage(wrong, "image/webp", 640, 480, 20_000)
            }
        }
    }

    @Test
    fun `an image has measures`() {
        for ((width, height, size) in listOf(
            Triple(0, 1, 1L),
            Triple(1, 0, 1L),
            Triple(1, 1, 0L),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                StoredImage(sha, "image/webp", width, height, size)
            }
        }
    }

    @Test
    fun `the kinds are the stored values of the data model`() {
        assertEquals(
            listOf("PRODUCT_PHOTO", "DOCUMENT_ATTACHMENT", "PAYMENT_QR", "BUSINESS_LOGO"),
            MediaKind.entries.map { it.name },
        )
    }
}
