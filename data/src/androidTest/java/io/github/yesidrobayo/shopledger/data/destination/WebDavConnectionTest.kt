package io.github.yesidrobayo.shopledger.data.destination

import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.crypto.KeystoreSealer
import io.github.yesidrobayo.shopledger.data.destination.WebDavCredentialStoreTest.Companion.SENTINEL_PASSWORD
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyInstant
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Conectar un servicio WebDAV contra el servicio falso en memoria: la prueba de conexión, lo que
 * se guarda solo si pasa, `link.json` sin secretos, y el destino de producción que abre el trabajo
 * de respaldo con la credencial sellada.
 */
class WebDavConnectionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.noBackupFilesDir, "webdav-connection-test")
    private val aliases = "test-webdav-connection-a" to "test-webdav-connection-b"
    private val credentials = WebDavCredentialStore({ directory }, Dispatchers.IO, aliases)
    private val links = DestinationLinkStore({ directory }, Dispatchers.IO)
    private val servers = ArrayList<FakeHttpServer>()
    private val recorded = ArrayList<DestinationLink>()

    @Before
    fun clean() {
        directory.deleteRecursively()
        listOf(aliases.first, aliases.second).forEach { KeystoreSealer(it).deleteKey() }
    }

    @After
    fun cleanUp() {
        servers.forEach { it.close() }
        clean()
    }

    private fun dav(
        override: (RecordedRequest) -> FakeResponse? = {
            null
        },
    ): Pair<InMemoryWebDav, FakeHttpServer> {
        val dav = InMemoryWebDav(override = override)
        val server = FakeHttpServer(keepAlive = true, handler = dav::handle).also { servers += it }
        return dav to server
    }

    /**
     * El cliente de la app, que en la prueba lleva toda petición al servidor falso, en claro. La
     * app arma la petición, y su cabecera de autenticación, con la dirección `https://` guardada.
     */
    private fun http(server: FakeHttpServer) = testHttp()
        .newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val local = request.url.newBuilder().scheme(
                "http",
            ).host("127.0.0.1").port(server.port).build()
            chain.proceed(request.newBuilder().url(local).build())
        }.build()

    private suspend fun connect(
        server: FakeHttpServer,
        url: String = BASE_URL,
        previous: List<String> = emptyList(),
        recordOk: Boolean = true,
    ): ConnectResult = WebDavConnector(http(server), credentials, links)
        .connect(url, TEST_USER, SENTINEL_PASSWORD, APP_NAME, previous, NOW) {
            recorded += it
            recordOk
        }

    private val copyName =
        BackupFileName(
            3,
            1,
            requireNotNull(CopyInstant.parse("20261107T120000Z")),
            "0a1b2c3d",
            BackupFileFormat.GZIP,
        )

    // Prueba de conexión

    @Test
    fun aPassingCheckSealsRecordsAndWritesTheLinkWithoutSecrets() = runBlocking<Unit> {
        val (dav, server) = dav()
        val result = connect(server) as ConnectResult.Connected
        assertFalse(result.hasCopies)
        assertTrue(result.linkSaved)
        assertTrue("/dav/$APP_NAME/" in dav.collections)
        // La prueba no deja nada en el servicio.
        assertTrue(dav.files.isEmpty())
        val methods = server.requests.map { it.method }
        assertEquals(
            "PROPFIND PROPFIND MKCOL DELETE DELETE PUT MOVE PUT MOVE GET DELETE DELETE".split(" "),
            methods,
        )
        assertEquals(
            "F",
            server.requests.filter {
                it.method == "MOVE"
            }.map { it.header("Overwrite") }.distinct().single(),
        )

        val link = recorded.single()
        assertEquals(BackupDestinationKind.WEBDAV, link.kind)
        assertEquals(TEST_USER, link.account)
        assertEquals("$BASE_URL$APP_NAME/", link.folderUrl)
        assertEquals(NOW, link.connectedAtMillis)
        assertEquals(link, links.read())
        val linkText = File(directory, DestinationLinkStore.FILE_NAME).readText()
        assertFalse(linkText.contains(SENTINEL_PASSWORD))
        assertTrue(linkText.contains("\"destination_id\""))
        val usable = credentials.read(link.destinationId) as CredentialRead.Usable
        assertEquals(SENTINEL_PASSWORD, usable.credential.password)
        // Solo dos archivos en la carpeta propia: el sellado y link.json.
        assertEquals(
            setOf(WebDavCredentialStore.FILE_NAME, DestinationLinkStore.FILE_NAME),
            directory.list()?.toSet(),
        )
    }

    @Test
    fun theSameAddressAndUserGiveTheSameDestination() = runBlocking<Unit> {
        val (_, server) = dav()
        val first = (connect(server) as ConnectResult.Connected).link.destinationId
        val second =
            (
                connect(
                    server,
                    url = "https://WEBDAV.example.com:443/dav",
                ) as ConnectResult.Connected
                ).link.destinationId
        assertEquals(first, second)
    }

    @Test
    fun httpIsRejectedWithoutAnyRequest() = runBlocking<Unit> {
        val (_, server) = dav()
        for ((url, reason) in listOf(
            "http://webdav.example.com/dav/" to EndpointRejection.NOT_HTTPS,
            "https://persona:clave@example.com/" to EndpointRejection.CREDENTIALS_IN_URL,
            "https://webdav.example.com/?x=1" to EndpointRejection.QUERY_OR_FRAGMENT,
        )) {
            assertEquals(url, ConnectResult.AddressRejected(reason), connect(server, url = url))
        }
        assertTrue(server.requests.isEmpty())
        assertFalse(directory.exists())
    }

    @Test
    fun rateLimitingStopsAtOnceAndSavesNothing() = runBlocking<Unit> {
        val (_, server) = dav { FakeResponse.status(429, mapOf("Retry-After" to "3600")) }
        assertEquals(
            ConnectResult.CheckFailed(ConnectionStep.ADDRESS, ConnectionFailure.RateLimited),
            connect(server),
        )
        assertEquals(1, server.requests.size)
        assertTrue(recorded.isEmpty())
        assertFalse(File(directory, WebDavCredentialStore.FILE_NAME).exists())
        assertFalse(File(directory, DestinationLinkStore.FILE_NAME).exists())
    }

    @Test
    fun aRejectedPasswordSavesNothingAndIsNotRepeated() = runBlocking<Unit> {
        val (_, server) = dav { FakeResponse.status(401) }
        assertEquals(
            ConnectResult.CheckFailed(ConnectionStep.ADDRESS, ConnectionFailure.CredentialRejected),
            connect(server),
        )
        assertEquals(1, server.requests.size)
        assertFalse(directory.exists() && directory.list().orEmpty().isNotEmpty())
    }

    @Test
    fun aServiceThatOverwritesFailsTheCheckAndCleansUp() = runBlocking<Unit> {
        val dav = InMemoryWebDav(ignoreOverwrite = true)
        val server = FakeHttpServer(keepAlive = true, handler = dav::handle).also { servers += it }
        assertEquals(
            ConnectResult.CheckFailed(
                ConnectionStep.NO_OVERWRITE,
                ConnectionFailure.OverwriteNotRespected,
            ),
            connect(server),
        )
        assertTrue(dav.files.isEmpty())
        // La carpeta nunca se borra.
        assertTrue("/dav/$APP_NAME/" in dav.collections)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun aRejectedTemporaryNameIsUnsupported() = runBlocking<Unit> {
        val (_, server) = dav { if (it.method == "PUT") FakeResponse.status(400) else null }
        assertEquals(
            ConnectResult.CheckFailed(
                ConnectionStep.WRITE,
                ConnectionFailure.Unsupported(UnsupportedReason.NAME_REJECTED),
            ),
            connect(server),
        )
    }

    @Test
    fun aBaseWithoutParentIsInvalid() = runBlocking<Unit> {
        val (_, server) = dav { if (it.method == "MKCOL") FakeResponse.status(409) else null }
        assertEquals(
            ConnectResult.CheckFailed(
                ConnectionStep.CREATE_FOLDER,
                ConnectionFailure.Unsupported(UnsupportedReason.BASE_URL_INVALID),
            ),
            connect(server),
        )
        assertEquals(1, server.requests.count { it.method == "MKCOL" })
    }

    @Test
    fun aPreviousNameWithCopiesKeepsItsFolder() = runBlocking<Unit> {
        val (dav, server) = dav()
        dav.collections += "/dav/$APP_NAME/"
        dav.collections += "/dav/$OLD_NAME/"
        dav.files["/dav/$OLD_NAME/${copyName.fileName}"] = ByteArray(10)
        val result = connect(server, previous = listOf(OLD_NAME)) as ConnectResult.Connected
        assertTrue(result.hasCopies)
        assertEquals("$BASE_URL$OLD_NAME/", result.link.folderUrl)
        // Si la carpeta del nombre actual ya tiene copias, manda esa.
        dav.files["/dav/$APP_NAME/${copyName.fileName}"] = ByteArray(10)
        val again = connect(server, previous = listOf(OLD_NAME)) as ConnectResult.Connected
        assertEquals("$BASE_URL$APP_NAME/", again.link.folderUrl)
    }

    @Test
    fun ifTheDatabaseIsNotWrittenTheCredentialDoesNotStay() = runBlocking<Unit> {
        val (_, server) = dav()
        assertEquals(ConnectResult.SaveFailed, connect(server, recordOk = false))
        assertFalse(File(directory, WebDavCredentialStore.FILE_NAME).exists())
        assertFalse(File(directory, DestinationLinkStore.FILE_NAME).exists())
    }

    // Destino de producción

    private fun opener(server: FakeHttpServer) = WebDavDestinationOpener(http(server), credentials)

    @Test
    fun theWorkerOpensTheDestinationFromTheSealedCredential() = runBlocking<Unit> {
        val (dav, server) = dav()
        val link = (connect(server) as ConnectResult.Connected).link
        dav.files["/dav/$APP_NAME/${copyName.fileName}"] = ByteArray(10)
        val opened = opener(
            server,
        ).open(link.destinationId, requireNotNull(link.folderUrl)) as DestinationOpen.Opened
        val listing = opened.destination.listCopies(
            TransferControl.Unbounded,
        ) as DestinationResult.Success
        assertEquals(copyName, listing.value.copies.single().name)
    }

    @Test
    fun anotherDestinationOrAFolderOutsideTheAddressLosesThePermission() = runBlocking<Unit> {
        val (_, server) = dav()
        val link = (connect(server) as ConnectResult.Connected).link
        val folder = requireNotNull(link.folderUrl)
        val opener = opener(server)
        assertEquals(
            DestinationOpen.PermissionLost,
            opener.open("ffffffffffffffffffffffffffffffff", folder),
        )
        for (outside in listOf(
            "http://webdav.example.com/dav/$APP_NAME/",
            "https://otro.example.com/dav/$APP_NAME/",
            "https://webdav.example.com:8443/dav/$APP_NAME/",
            "https://webdav.example.com/otra/$APP_NAME/",
            "https://webdav.example.com/dav/",
            "https://webdav.example.com/dav/$APP_NAME",
            "https://webdav.example.com/dav/$APP_NAME/?x=1",
            "no es una dirección",
        )) {
            assertEquals(
                outside,
                DestinationOpen.PermissionLost,
                opener.open(link.destinationId, outside),
            )
        }
        credentials.delete()
        assertEquals(DestinationOpen.PermissionLost, opener.open(link.destinationId, folder))
    }

    @Test
    fun a401InTheWorkerIsPermissionLostWithASingleRequest() = runBlocking<Unit> {
        val (_, server) = dav()
        val link = (connect(server) as ConnectResult.Connected).link
        val denied = FakeHttpServer(keepAlive = true) { FakeResponse.status(401) }.also {
            servers +=
                it
        }
        val opened =
            opener(
                denied,
            ).open(link.destinationId, requireNotNull(link.folderUrl)) as DestinationOpen.Opened
        val listing = opened.destination.listCopies(TransferControl.Unbounded)
        assertEquals(DestinationResult.Failure(DestinationFailure.PermissionLost()), listing)
        assertEquals(1, denied.requests.size)
    }

    @Test
    fun theLinkReadsBackAndADamagedOneIsAbsent() = runBlocking<Unit> {
        val link =
            DestinationLink(
                BackupDestinationKind.WEBDAV,
                "persona",
                "abc",
                "https://x.example/a/",
                5,
            )
        links.write(link)
        assertEquals(link, links.read())
        File(directory, DestinationLinkStore.FILE_NAME).writeText("{no es json")
        assertEquals(null, links.read())
        assertTrue(links.delete())
        assertEquals(null, links.read())
        assertFalse(link.toString().contains("persona"))
    }

    private companion object {
        const val BASE_URL = "https://webdav.example.com/dav/"
        const val APP_NAME = "TiendaDePrueba"
        const val OLD_NAME = "NombreAnterior"
        const val NOW = 1_794_000_000_000L
    }
}
