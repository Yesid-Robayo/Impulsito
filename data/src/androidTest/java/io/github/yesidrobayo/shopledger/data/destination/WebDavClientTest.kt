package io.github.yesidrobayo.shopledger.data.destination

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParser

/**
 * Cada fila de la tabla de respuestas por método que el cliente decide solo, con un servidor falso
 * propio. Las filas que dependen de quien llama, como reintentar o pasar a la recuperación, se
 * prueban por el resultado que el cliente entrega.
 */
class WebDavClientTest {
    private val servers = ArrayList<FakeHttpServer>()

    private fun server(bindAll: Boolean = false, handler: (RecordedRequest) -> FakeResponse) =
        FakeHttpServer(bindAll = bindAll, handler = handler).also { servers += it }

    @After
    fun closeServers() = servers.forEach { it.close() }

    private val file = APP_FOLDER.file(
        "shopledger-backup-g00000001-s001-20261106T120000Z-0a1b2c3d.db.gz",
    )
    private val staged = APP_FOLDER.file("${file.name}.sltmp")
    private val octets = "application/octet-stream"

    private fun listingOf(result: PropfindResult): WebDavListing =
        (result as PropfindResult.Listed).listing

    private fun unsupported(result: Any): UnsupportedReason? {
        val failure =
            when (result) {
                is PropfindResult.Failed -> result.failure
                is MkcolResult.Failed -> result.failure
                is PutResult.Failed -> result.failure
                is MoveResult.Failed -> result.failure
                is GetResult.Failed -> result.failure
                is DeleteResult.Failed -> result.failure
                is RangeResult.Failed -> result.failure
                else -> null
            }
        return (failure as? WebDavFailure.Unsupported)?.reason
    }

    private fun sha256Of(bytes: ByteArray) = newSha256().digest(bytes).toLowerHex()

    private fun propfind(
        server: FakeHttpServer,
        depth: WebDavClient.Depth = WebDavClient.Depth.ONE,
    ) = runBlocking { testClient(server).propfind(APP_FOLDER, depth) }

    // PROPFIND

    @Test
    fun propfind207IsParsedWithChildrenQuotaAndHeaders() {
        val server =
            server {
                FakeResponse.xml(
                    207,
                    Dav.multistatus(
                        Dav.folder("/dav/app/", quota = "1048576"),
                        Dav.file("/dav/app/${file.name}", "4096"),
                        Dav.folder("http://${it.header("Host")}/dav/app/media/"),
                    ),
                )
            }
        val listing = listingOf(propfind(server))
        assertTrue(listing.complete)
        assertEquals(1_048_576L, listing.quotaAvailableBytes)
        assertEquals(listOf(file.name, "media"), listing.children.map { it.name })
        assertEquals(4096L, listing.children[0].contentLength)
        assertTrue(listing.children[1].isCollection)
        val request = server.requests.single()
        assertEquals("PROPFIND", request.method)
        assertEquals("/dav/app/", request.path)
        assertEquals("1", request.header("Depth"))
        assertEquals("identity", request.header("Accept-Encoding"))
        assertTrue(String(request.body, Charsets.UTF_8).contains("quota-available-bytes"))
    }

    @Test
    fun basicAuthorizationTravelsInUtf8() {
        val server = server { FakeResponse.status(404) }
        propfind(server)
        val header = requireNotNull(server.requests.single().header("Authorization"))
        val decoded =
            String(Base64.getDecoder().decode(header.removePrefix("Basic ")), Charsets.UTF_8)
        assertEquals("$TEST_USER:$TEST_PASSWORD", decoded)
    }

    @Test
    fun propfindWithAnErrorInsideLeavesTheListIncomplete() {
        val withBadResponse =
            server {
                FakeResponse.xml(
                    207,
                    Dav.multistatus(
                        Dav.folder("/dav/app/"),
                        Dav.file(
                            "/dav/app/a.db.gz",
                            "1",
                            status = "HTTP/1.1 500 Internal Server Error",
                        ),
                    ),
                )
            }
        assertEquals(false, listingOf(propfind(withBadResponse)).complete)
        val withResponseStatus =
            server {
                FakeResponse.xml(
                    207,
                    Dav.multistatus(
                        Dav.folder("/dav/app/"),
                        "<d:response><d:href>/dav/app/b.db.gz</d:href>" +
                            "<d:status>HTTP/1.1 403 Forbidden</d:status></d:response>",
                    ),
                )
            }
        assertEquals(false, listingOf(propfind(withResponseStatus)).complete)
        val withoutLength =
            server {
                FakeResponse.xml(
                    207,
                    Dav.multistatus(
                        Dav.folder("/dav/app/"),
                        "<d:response><d:href>/dav/app/c.db.gz</d:href><d:propstat><d:prop>" +
                            "<d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status>" +
                            "</d:propstat><d:propstat><d:prop><d:getcontentlength/></d:prop>" +
                            "<d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>",
                    ),
                )
            }
        assertEquals(false, listingOf(propfind(withoutLength)).complete)
    }

    @Test
    fun aFolderWithoutSizeDoesNotLeaveTheListIncomplete() {
        val server =
            server {
                FakeResponse.xml(
                    207,
                    Dav.multistatus(
                        Dav.folder("/dav/app/"),
                        "<d:response><d:href>/dav/app/media/</d:href><d:propstat><d:prop>" +
                            "<d:resourcetype><d:collection/></d:resourcetype></d:prop>" +
                            "<d:status>HTTP/1.1 200 OK</d:status></d:propstat><d:propstat>" +
                            "<d:prop><d:getcontentlength/></d:prop>" +
                            "<d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>",
                    ),
                )
            }
        val listing = listingOf(propfind(server))
        assertTrue(listing.complete)
        assertTrue(listing.children.single().isCollection)
    }

    @Test
    fun propfindStatusesFollowTheTable() {
        assertEquals(PropfindResult.NotFound, propfind(server { FakeResponse.status(404) }))
        assertEquals(
            PropfindResult.Failed(WebDavFailure.PermissionLost(403)),
            propfind(server { FakeResponse.status(403) }),
        )
        assertEquals(
            PropfindResult.Failed(WebDavFailure.PermissionLost(401)),
            propfind(server { FakeResponse.status(401) }),
        )
        // Solo 207 vale en PROPFIND: un 200 cae en la fila por defecto.
        assertEquals(
            UnsupportedReason.UNEXPECTED_STATUS,
            unsupported(propfind(server { FakeResponse.status(200) })),
        )
    }

    @Test
    fun doctypeIsRejectedEvenThoughNextWouldSkipIt() {
        val xml =
            """<?xml version="1.0"?><!DOCTYPE d:multistatus [<!ENTITY x "y">]>""" +
                Dav.multistatus(Dav.folder("/dav/app/")).substringAfter("?>")
        val server = server { FakeResponse.xml(207, xml) }
        assertEquals(UnsupportedReason.INVALID_RESPONSE, unsupported(propfind(server)))

        // El mismo documento con next(): llega a la raíz sin ver el DOCTYPE.
        val parser = android.util.Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(xml.byteInputStream(), "UTF-8")
        assertEquals(XmlPullParser.START_TAG, parser.next())
        assertEquals("multistatus", parser.name)
    }

    @Test
    fun entitiesWithoutReplacementAreRejectedAndBuiltInOnesAreJoined() {
        val unknown =
            server {
                FakeResponse.xml(
                    207,
                    Dav.multistatus(Dav.folder("/dav/app/"), Dav.file("/dav/app/a&bogus;b", "1")),
                )
            }
        assertEquals(UnsupportedReason.INVALID_RESPONSE, unsupported(propfind(unknown)))
        val builtIn =
            server {
                FakeResponse.xml(
                    207,
                    Dav.multistatus(Dav.folder("/dav/app/"), Dav.file("/dav/app/a&amp;b", "1")),
                )
            }
        assertEquals("a&b", listingOf(propfind(builtIn)).children.single().name)
    }

    @Test
    fun hrefOutsideTheFolderInvalidatesTheWholeResponse() {
        val hostile =
            listOf(
                "http://other.example/dav/app/x",
                "http://127.0.0.1:1/dav/app/x",
                "/dav/app/../x",
                "/dav/app/%2E%2E/x",
                "/dav/app/a%2Fb",
                "/dav/app/a%5Cb",
                "/dav/app/a%00b",
                "/dav/other/x",
                "/dav/app/sub/x",
                "app/x",
                "/dav/app/a%zz",
            )
        for (href in hostile) {
            val server =
                server {
                    FakeResponse.xml(
                        207,
                        Dav.multistatus(Dav.folder("/dav/app/"), Dav.file(href, "1")),
                    )
                }
            assertEquals(href, UnsupportedReason.INVALID_RESPONSE, unsupported(propfind(server)))
        }
        // Codificado distinto, pero el mismo nombre: se acepta, y el nombre queda decodificado.
        val encoded =
            server {
                FakeResponse.xml(
                    207,
                    Dav.multistatus(Dav.folder("/d%61v/app"), Dav.file("/dav/app/a%20b", "1")),
                )
            }
        assertEquals("a b", listingOf(propfind(encoded)).children.single().name)
    }

    @Test
    fun quotaComesOnlyFromTheFolderAndNegativeMeansUnknown() {
        fun quota(value: String?, childQuota: String? = null): Long? {
            val child =
                childQuota?.let {
                    "<d:response><d:href>/dav/app/x</d:href><d:propstat><d:prop><d:resourcetype/>" +
                        "<d:getcontentlength>1</d:getcontentlength>" +
                        "<d:quota-available-bytes>$it</d:quota-available-bytes></d:prop>" +
                        "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
                } ?: ""
            val server =
                server {
                    FakeResponse.xml(207, Dav.multistatus(Dav.folder("/dav/app/", value), child))
                }
            return listingOf(propfind(server)).quotaAvailableBytes
        }
        assertEquals(10L, quota("10"))
        assertNull(quota("-3"))
        assertNull(quota("mucho"))
        assertNull(quota(null))
        assertNull(quota(null, childQuota = "99"))
    }

    @Test
    fun contentLengthMustBeANonNegativeInteger() {
        for (length in listOf("-1", "1.5", "abc", "+1", "9999999999999999")) {
            val server =
                server {
                    FakeResponse.xml(
                        207,
                        Dav.multistatus(Dav.folder("/dav/app/"), Dav.file("/dav/app/x", length)),
                    )
                }
            assertEquals(length, UnsupportedReason.INVALID_RESPONSE, unsupported(propfind(server)))
        }
    }

    @Test
    fun xmlLimitsMakeTheResponseInvalid() {
        val deep = "<d:a>".repeat(16) + "</d:a>".repeat(16)
        val tooDeep =
            server { FakeResponse.xml(207, Dav.multistatus(Dav.folder("/dav/app/"), deep)) }
        assertEquals(UnsupportedReason.INVALID_RESPONSE, unsupported(propfind(tooDeep)))
        val longText = Dav.file("/dav/app/" + "a".repeat(5000), "1")
        val tooLong =
            server { FakeResponse.xml(207, Dav.multistatus(Dav.folder("/dav/app/"), longText)) }
        assertEquals(UnsupportedReason.INVALID_RESPONSE, unsupported(propfind(tooLong)))
        val padding = " ".repeat(1024 * 1024)
        val huge = Dav.multistatus(Dav.folder("/dav/app/") + "<!--$padding-->".repeat(8))
        val tooBig = server { FakeResponse.xml(207, huge) }
        assertEquals(UnsupportedReason.INVALID_RESPONSE, unsupported(propfind(tooBig)))
        // Justo debajo del tope de profundidad, sí pasa.
        val fine = "<d:a>".repeat(14) + "</d:a>".repeat(14)
        val atLimit =
            server { FakeResponse.xml(207, Dav.multistatus(Dav.folder("/dav/app/"), fine)) }
        assertTrue(listingOf(propfind(atLimit)).children.isEmpty())
    }

    @Test
    fun encodedResponsesAreNeverDecompressed() {
        val gzipList =
            server {
                FakeResponse.xml(
                    207,
                    Dav.multistatus(Dav.folder("/dav/app/")),
                    mapOf("Content-Encoding" to "gzip"),
                )
            }
        assertEquals(UnsupportedReason.INVALID_RESPONSE, unsupported(propfind(gzipList)))
        val gzipGet =
            server {
                FakeResponse.Reply(200, mapOf("Content-Encoding" to "gzip"), byteArrayOf(1, 2, 3))
            }
        val got = runBlocking { testClient(gzipGet).getBytes(file, 1024).first }
        assertEquals(UnsupportedReason.INVALID_RESPONSE, unsupported(got))
        assertEquals("identity", gzipGet.requests.single().header("Accept-Encoding"))
    }

    // MKCOL

    @Test
    fun mkcolStatusesFollowTheTable() {
        fun mkcol(code: Int): Pair<MkcolResult, Int> {
            val server = server { FakeResponse.status(code) }
            val result = runBlocking { testClient(server).mkcol(APP_FOLDER) }
            return result to server.requests.size
        }
        assertEquals(MkcolResult.Created to 1, mkcol(201))
        assertEquals(MkcolResult.AlreadyExists to 1, mkcol(405))
        // 409: falta un ancestro. Una sola petición, sin bucle.
        assertEquals(MkcolResult.MissingAncestor to 1, mkcol(409))
        assertEquals(UnsupportedReason.UNEXPECTED_STATUS, unsupported(mkcol(207).first))
    }

    // PUT

    @Test
    fun putStreamsWithContentLengthAndFollowsTheTable() {
        val bytes = ByteArray(10_000) { (it % 251).toByte() }
        var answer = 201
        val server = server { FakeResponse.status(answer) }
        val client = testClient(server)
        for (code in listOf(200, 201, 204)) {
            answer = code
            assertEquals(
                PutResult.Stored(code),
                runBlocking {
                    client.putBytes(staged, bytes, octets)
                },
            )
        }
        val request = server.requests.last()
        assertEquals("PUT", request.method)
        assertEquals("/dav/app/${staged.name}", request.path)
        assertEquals("10000", request.header("Content-Length"))
        assertArrayEquals(bytes, request.body)
        for (code in listOf(404, 409)) {
            answer = code
            assertEquals(
                PutResult.MissingFolder(code),
                runBlocking { client.putBytes(staged, bytes, octets) },
            )
        }
        answer = 400
        assertEquals(
            UnsupportedReason.NAME_REJECTED,
            unsupported(runBlocking { client.putBytes(staged, bytes, octets) }),
        )
    }

    // MOVE

    @Test
    fun moveSendsOverwriteFalseAndADestinationFromTheStoredUrl() {
        var answer = 201
        val server = server { FakeResponse.status(answer) }
        val client = testClient(server)
        assertEquals(MoveResult.Moved(201), runBlocking { client.move(staged, file) })
        val request = server.requests.single()
        assertEquals("MOVE", request.method)
        assertEquals("F", request.header("Overwrite"))
        assertEquals(server.url() + "app/${file.name}", request.header("Destination"))
        val expected =
            mapOf(
                204 to MoveResult.Moved(204),
                404 to MoveResult.NotFound,
                409 to MoveResult.Conflict,
                412 to MoveResult.TargetExists,
            )
        for ((code, result) in expected) {
            answer = code
            assertEquals(result, runBlocking { client.move(staged, file) })
        }
        answer = 400
        assertEquals(
            UnsupportedReason.NAME_REJECTED,
            unsupported(
                runBlocking {
                    client.move(staged, file)
                },
            ),
        )
        answer = 207
        assertEquals(
            UnsupportedReason.UNEXPECTED_STATUS,
            unsupported(
                runBlocking {
                    client.move(staged, file)
                },
            ),
        )
    }

    @Test
    fun moveCutAfterArrivingIsNotRepeatedByOkHttp() {
        // Una conexión que ya sirvió una petición y se corta con el MOVE: es el caso en que OkHttp
        // repite solo la petición si retryOnConnectionFailure está encendido.
        fun cutting() = FakeHttpServer(keepAlive = true) {
            if (it.method == "MOVE") FakeResponse.Drop else FakeResponse.status(404)
        }.also { servers += it }
        val server = cutting()
        val client = testClient(server)
        runBlocking { client.delete(file) }
        val result = runBlocking { client.move(staged, file) }
        assertEquals(MoveResult.Failed(WebDavFailure.Unreachable(TransportFailure.NETWORK)), result)
        assertEquals(1, server.requests.count { it.method == "MOVE" })

        // Control: el mismo caso con el reintento de OkHttp encendido repite el MOVE.
        val retrying = cutting()
        val careless =
            testClient(
                retrying,
                http = testHttp().newBuilder().retryOnConnectionFailure(true).build(),
            )
        runBlocking { careless.delete(file) }
        runBlocking { careless.move(staged, file) }
        assertTrue(retrying.requests.count { it.method == "MOVE" } > 1)
    }

    @Test
    fun cancellingTheCoroutineCutsTheCallInFlight() {
        val server =
            server {
                Thread.sleep(10_000)
                FakeResponse.status(200)
            }
        val started = System.nanoTime()
        val result =
            runBlocking { withTimeoutOrNull(300) { testClient(server).getBytes(file, 10).first } }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertNull(result)
        assertTrue("tardó $elapsedMillis ms", elapsedMillis < 3_000)
    }

    // GET y DELETE

    @Test
    fun getReadsUpToTheLimit() {
        val bytes = ByteArray(2048) { it.toByte() }
        val server = server { FakeResponse.Reply(200, body = bytes) }
        val client = testClient(server)
        val (got, body) = runBlocking { client.getBytes(file, 2048) }
        assertArrayEquals(bytes, body)
        assertEquals(GetResult.Downloaded(2048, sha256Of(bytes)), got)
        assertEquals(GetResult.TooLarge, runBlocking { client.getBytes(file, 2047).first })
        assertEquals(
            GetResult.NotFound,
            runBlocking {
                testClient(server { FakeResponse.status(404) }).getBytes(file, 10).first
            },
        )
    }

    @Test
    fun deleteTreats404AsDone() {
        for (code in listOf(200, 204, 404)) {
            val server = server { FakeResponse.status(code) }
            assertEquals(
                DeleteResult.Deleted(code),
                runBlocking {
                    testClient(server).delete(file)
                },
            )
        }
    }

    // Filas comunes

    @Test
    fun commonRowsMeanTheSameInEveryMethod() {
        val calls: List<suspend (WebDavClient) -> Any> =
            listOf(
                { it.mkcol(APP_FOLDER) },
                { it.putBytes(staged, ByteArray(1), octets) },
                { it.move(staged, file) },
                { it.getBytes(file, 10).first },
                { it.delete(file) },
            )
        for (call in calls) {
            fun failure(code: Int, headers: Map<String, String> = emptyMap()): WebDavFailure? {
                val server = server { FakeResponse.status(code, headers) }
                val result = runBlocking { call(testClient(server)) }
                return when (result) {
                    is MkcolResult.Failed -> result.failure
                    is PutResult.Failed -> result.failure
                    is MoveResult.Failed -> result.failure
                    is GetResult.Failed -> result.failure
                    is DeleteResult.Failed -> result.failure
                    else -> null
                }
            }
            assertEquals(WebDavFailure.PermissionLost(401), failure(401))
            assertEquals(WebDavFailure.Unsupported(UnsupportedReason.FORBIDDEN, 403), failure(403))
            assertEquals(
                WebDavFailure.Unsupported(UnsupportedReason.TOO_LARGE, 413),
                failure(413),
            )
            assertEquals(
                WebDavFailure.Unsupported(UnsupportedReason.NOT_IMPLEMENTED, 501),
                failure(501),
            )
            assertEquals(WebDavFailure.QuotaExceeded(507), failure(507))
            assertEquals(WebDavFailure.Transient(423, null), failure(423))
            assertEquals(WebDavFailure.Transient(429, 7), failure(429, mapOf("Retry-After" to "7")))
            assertEquals(
                WebDavFailure.Transient(503, null),
                failure(503, mapOf("Retry-After" to "Fri, 06 Nov 2026 12:00:00 GMT")),
            )
            assertEquals(WebDavFailure.Transient(500, null), failure(500))
            assertEquals(
                WebDavFailure.Unsupported(UnsupportedReason.UNEXPECTED_STATUS, 418),
                failure(418),
            )
        }
        // 405 fuera de MKCOL: el servicio no admite ese método.
        val server = server { FakeResponse.status(405) }
        assertEquals(
            UnsupportedReason.METHOD_NOT_ALLOWED,
            unsupported(runBlocking { testClient(server).move(staged, file) }),
        )
    }

    // Redirecciones

    @Test
    fun redirectToTheSameUrlWithSlashIsRepeatedOnceFromTheStoredUrl() {
        val server =
            server {
                if (it.path == "/dav/app") {
                    FakeResponse.status(301, mapOf("Location" to "/dav/app/"))
                } else {
                    FakeResponse.status(404)
                }
            }
        // Pedimos la carpeta sin barra con una ruta de archivo para forzar el caso.
        val result =
            runBlocking { testClient(server).getBytes(WebDavPath(emptyList(), "app"), 10).first }
        assertEquals(GetResult.NotFound, result)
        assertEquals(listOf("/dav/app", "/dav/app/"), server.requests.map { it.path })
    }

    @Test
    fun redirectLoopToTheSameUrlStopsAfterOneRepeat() {
        val server = server { FakeResponse.status(301, mapOf("Location" to it.path)) }
        val result = runBlocking { testClient(server).delete(file) }
        assertEquals(UnsupportedReason.REDIRECT, unsupported(result))
        assertEquals(2, server.requests.size)
    }

    @Test
    fun redirectElsewhereIsNeverFollowedAndAuthorizationNeverLeaves() {
        val other = server(bindAll = true) { FakeResponse.status(200) }
        // Comprobación previa: ese destino sí responde si alguien lo sigue.
        OkHttpClient().newCall(
            Request.Builder().url(other.url("localhost", "/x")).build(),
        ).execute().close()
        assertEquals(1, other.requests.size)

        val targets =
            listOf(
                other.url("localhost", "/dav/app/x"),
                other.url("127.0.0.1", "/dav/app/x"),
                "https://127.0.0.1:${other.port}/dav/app/x",
                "/dav/otra/x",
            )
        for (location in targets) {
            val server = server { FakeResponse.status(302, mapOf("Location" to location)) }
            val result = runBlocking { testClient(server).getBytes(file, 10).first }
            assertEquals(location, UnsupportedReason.REDIRECT, unsupported(result))
            assertEquals(location, 1, server.requests.size)
        }
        assertEquals(1, other.requests.size)
    }

    // Rangos y subida en flujo

    @Test
    fun aRangeIsAcceptedOnlyWithTheExactContentRange() {
        val total = 1000L
        val bytes = ByteArray(10) { it.toByte() }
        fun range(headers: Map<String, String>, body: ByteArray = bytes): RangeResult {
            val server = server { FakeResponse.Reply(206, headers, body) }
            return runBlocking { testClient(server).getRange(file, 990, 999, total) }
        }
        val exact = range(mapOf("Content-Range" to "bytes 990-999/1000"))
        assertArrayEquals(bytes, (exact as RangeResult.Partial).bytes)
        for (other in listOf("bytes 0-9/1000", "bytes 990-999/2000", "bytes */1000", "")) {
            assertEquals(
                other,
                UnsupportedReason.INVALID_RESPONSE,
                unsupported(range(mapOf("Content-Range" to other))),
            )
        }
        // Un byte de más, o uno de menos, tampoco es el rango pedido.
        val longer = range(mapOf("Content-Range" to "bytes 990-999/1000"), ByteArray(11))
        assertEquals(UnsupportedReason.INVALID_RESPONSE, unsupported(longer))
        val shorter = range(mapOf("Content-Range" to "bytes 990-999/1000"), ByteArray(9))
        assertEquals(UnsupportedReason.INVALID_RESPONSE, unsupported(shorter))
        val server = server { FakeResponse.status(404) }
        assertEquals(
            RangeResult.NotFound,
            runBlocking {
                testClient(server).getRange(file, 0, 9, total)
            },
        )
        assertEquals("bytes=0-9", server.requests.single().header("Range"))
    }

    @Test
    fun putFileStreamsTheWholeFileWithItsLength() {
        val bytes = ByteArray(300_000) { (it % 247).toByte() }
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val local = File.createTempFile("put-file", ".db.gz", cache)
        try {
            local.writeBytes(bytes)
            val server = server { FakeResponse.status(201) }
            val result =
                runBlocking { testClient(server).putFile(staged, local, "application/gzip") }
            assertEquals(PutResult.Stored(201), result)
            val request = server.requests.single()
            assertEquals("300000", request.header("Content-Length"))
            assertEquals("application/gzip", request.header("Content-Type"))
            assertArrayEquals(bytes, request.body)
        } finally {
            local.delete()
        }
    }

    @Test
    fun theCallTimeoutComesFromTheTimeLeft() {
        val server =
            server {
                Thread.sleep(5_000)
                FakeResponse.status(200)
            }
        val control =
            object : TransferControl by TransferControl.Unbounded {
                override fun remainingMillis(): Long = 300
            }
        val started = System.nanoTime()
        val result = runBlocking { testClient(server).delete(file, control) }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertEquals(
            DeleteResult.Failed(WebDavFailure.Unreachable(TransportFailure.TIMEOUT)),
            result,
        )
        assertTrue("tardó $elapsedMillis ms", elapsedMillis < 3_000)
    }
}
