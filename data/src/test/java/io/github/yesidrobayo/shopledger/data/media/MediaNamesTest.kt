package io.github.yesidrobayo.shopledger.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los nombres de las fotos en el servicio y su subcarpeta: cada foto, su `.slmeta` y su temporal
 * van en la subcarpeta de los dos primeros caracteres de su suma, y un nombre que la app no
 * escribe no tiene subcarpeta, así que la lista de una subcarpeta nunca lo trae.
 */
class MediaNamesTest {
    private val sha = "ab" + "0123456789abcdef".repeat(3) + "0123456789abcd"

    @Test
    fun theSubfolderIsTheFirstTwoCharactersOfTheSum() {
        assertEquals(64, sha.length)
        assertEquals("ab", MediaNames.shardOf(sha))
    }

    @Test
    fun everyNameTheAppWritesBelongsToTheSubfolderOfItsSum() {
        val photo = MediaNames.photo(sha)
        val sealed = MediaNames.sealed(sha)
        val names =
            listOf(photo, sealed).flatMap { name ->
                val meta = MediaNames.metaOf(name)
                listOf(name, meta, MediaNames.temporaryOf(name), MediaNames.temporaryOf(meta))
            }

        for (name in names) assertEquals(name, "ab", MediaNames.shardOfName(name))
    }

    @Test
    fun aNameTheAppDoesNotWriteHasNoSubfolder() {
        val hostile =
            listOf(
                "",
                "ab",
                "ab.webp",
                sha,
                sha.uppercase() + ".webp",
                sha.dropLast(1) + ".webp",
                sha + "0.webp",
                sha + ".png",
                sha + ".webp.sltmp.sltmp",
                sha + ".webp.slmeta.slmeta",
                sha + ".slmeta",
                "../" + sha + ".webp",
                "ab/" + sha + ".webp",
                sha.replaceFirst('a', 'g') + ".webp",
                sha.replaceFirst('a', '\u0000') + ".webp",
            )

        for (name in hostile) assertNull(name, MediaNames.shardOfName(name))
    }

    @Test
    fun onlyTwoLowercaseHexCharactersNameASubfolder() {
        assertTrue(MediaNames.isShard("00"))
        assertTrue(MediaNames.isShard("af"))
        assertTrue(MediaNames.isShard("9f"))
        for (segment in listOf("", "a", "abc", "AB", "g0", "..", "a/", "./")) {
            assertFalse(segment, MediaNames.isShard(segment))
        }
    }

    @Test
    fun theListingOfASubfolderKeepsOnlyItsOwnNames() {
        val other = "cd" + sha.drop(2)
        val photo = MediaNames.photo(sha)
        val files =
            mapOf(
                photo to 10L,
                MediaNames.metaOf(photo) to 2L,
                MediaNames.temporaryOf(photo) to null,
                // De otra subcarpeta, con mayúsculas, o que no es una foto: no entran.
                MediaNames.photo(other) to 10L,
                MediaNames.metaOf(MediaNames.photo(other)) to 2L,
                sha.uppercase() + ".webp" to 10L,
                "notas.txt" to 1L,
                "../" + photo to 10L,
            )

        val listing = MediaListing.ofShard("ab", files, quota = 99L)

        assertEquals(
            setOf(photo, MediaNames.metaOf(photo), MediaNames.temporaryOf(photo)),
            listing.files.keys,
        )
        assertEquals(99L, listing.quotaAvailableBytes)
        assertEquals(setOf(sha), listing.plainSums())
        assertEquals(listOf(photo), listing.namesOf(sha))
        assertTrue(MediaListing.ofShard("cd", emptyMap(), null).files.isEmpty())
    }
}
