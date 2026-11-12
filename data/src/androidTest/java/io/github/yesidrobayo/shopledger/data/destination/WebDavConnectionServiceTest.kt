package io.github.yesidrobayo.shopledger.data.destination

import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.crypto.KeystoreSealer
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateEntity
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.CurrentDestination
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavAddress
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavAddressProblem
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavLogin
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavSaveResult
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavTestFailure
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavTestResult
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavTestStep
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Conectar desde la pantalla contra el servicio falso: la prueba dice cada paso y no guarda nada,
 * cada fallo llega con su paso y su causa, guardar sella, registra y escribe `link.json` solo con lo
 * probado, y el destino actual se lee para reconectar sin la contraseña.
 */
class WebDavConnectionServiceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.noBackupFilesDir, "webdav-service-test")
    private val aliases = "test-webdav-service-a" to "test-webdav-service-b"
    private val credentials = WebDavCredentialStore({ directory }, Dispatchers.IO, aliases)
    private val links = DestinationLinkStore({ directory }, Dispatchers.IO)
    private val servers = ArrayList<FakeHttpServer>()
    private val recorded = ArrayList<DestinationLink>()
    private var connectedCalls = 0
    private var row: BackupStateEntity? = null

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

    private fun server(
        override: (RecordedRequest) -> FakeResponse? = { null },
    ): Pair<InMemoryWebDav, FakeHttpServer> {
        val dav = InMemoryWebDav(override = override)
        val server = FakeHttpServer(keepAlive = true, handler = dav::handle).also { servers += it }
        return dav to server
    }

    /** El cliente de la app, que en la prueba lleva toda petición al servidor falso, en claro. */
    private var changeOutcome: ChangeOutcome? = null
    private val changes = ArrayList<DestinationLink>()

    private fun service(server: FakeHttpServer, recordOk: Boolean = true): WebDavConnectionService {
        val http = testHttp()
            .newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                val local = request.url.newBuilder().scheme("http").host("127.0.0.1")
                    .port(server.port).build()
                chain.proceed(request.newBuilder().url(local).build())
            }.build()
        return WebDavConnectionService(
            connector = WebDavConnector(http, credentials, links),
            state = { row },
            slotsLabel = { "Nube" },
            record = {
                recorded += it
                recordOk
            },
            backupLock = Mutex(),
            onConnected = { connectedCalls++ },
            network = { true },
            nowMillis = { NOW },
            change =
                changeOutcome?.let { outcome ->
                    { _, link ->
                        changes += link
                        outcome
                    }
                },
        )
    }

    private val login = WebDavLogin(BASE_URL, TEST_USER, TEST_PASSWORD)

    private fun nothingSaved() {
        assertTrue(recorded.isEmpty())
        assertFalse(File(directory, WebDavCredentialStore.FILE_NAME).exists())
        assertFalse(File(directory, DestinationLinkStore.FILE_NAME).exists())
        assertEquals(0, connectedCalls)
    }

    @Test
    fun theTestNamesEveryStepAndSavesNothing() = runBlocking<Unit> {
        val (dav, server) = server()
        val steps = ArrayList<WebDavTestStep>()

        val result = service(server).test(login, APP_NAME) { steps += it }

        val passed = result as WebDavTestResult.Passed
        assertEquals(APP_NAME, passed.tested.folderName)
        assertFalse(passed.tested.hasCopies)
        assertEquals(WebDavTestStep.entries, steps.distinct())
        assertTrue(dav.files.isEmpty())
        nothingSaved()
    }

    @Test
    fun savingSealsRecordsAndWritesTheLinkThenSchedules() = runBlocking<Unit> {
        val (_, server) = server()
        val service = service(server)
        val tested = (service.test(login, APP_NAME) {} as WebDavTestResult.Passed).tested

        assertEquals(WebDavSaveResult.Connected(hasCopies = false), service.save(login, tested))

        val link = recorded.single()
        assertEquals(tested.destinationId, link.destinationId)
        assertEquals("$BASE_URL$APP_NAME/", link.folderUrl)
        assertEquals(link, links.read())
        assertTrue(credentials.read(link.destinationId) is CredentialRead.Usable)
        assertEquals(1, connectedCalls)
    }

    @Test
    fun withAnotherDestinationSavingIsAChangeAndTheConnectorSealsNothing() = runBlocking<Unit> {
        changeOutcome = ChangeOutcome.CHANGED
        val (_, server) = server()
        val service = service(server)
        val tested = (service.test(login, APP_NAME) {} as WebDavTestResult.Passed).tested

        assertEquals(WebDavSaveResult.Connected(hasCopies = false), service.save(login, tested))

        // El cambio lo hace el servicio del enlace, con su credencial nueva al lado de la actual.
        assertEquals(tested.destinationId, changes.single().destinationId)
        assertTrue(recorded.isEmpty())
        assertFalse(File(directory, WebDavCredentialStore.FILE_NAME).exists())
        assertEquals(1, connectedCalls)
    }

    @Test
    fun aFailedChangeSavesNothingAndNotAChangeConnectsAsAlways() = runBlocking<Unit> {
        changeOutcome = ChangeOutcome.FAILED
        val (_, server) = server()
        val failing = service(server)
        val tested = (failing.test(login, APP_NAME) {} as WebDavTestResult.Passed).tested
        assertEquals(WebDavSaveResult.SaveFailed, failing.save(login, tested))
        nothingSaved()

        changeOutcome = ChangeOutcome.NOT_A_CHANGE
        val connecting = service(server)
        assertEquals(WebDavSaveResult.Connected(hasCopies = false), connecting.save(login, tested))
        assertEquals(tested.destinationId, recorded.single().destinationId)
    }

    @Test
    fun aChangedUserBetweenTestAndSaveSavesNothing() = runBlocking<Unit> {
        val (_, server) = server()
        val service = service(server)
        val tested = (service.test(login, APP_NAME) {} as WebDavTestResult.Passed).tested

        val other = WebDavLogin(BASE_URL, "otro-usuario", TEST_PASSWORD)

        assertEquals(WebDavSaveResult.SaveFailed, service.save(other, tested))
        nothingSaved()
    }

    @Test
    fun aRecordThatFailsLeavesNoCredential() = runBlocking<Unit> {
        val (_, server) = server()
        val service = service(server, recordOk = false)
        val tested = (service.test(login, APP_NAME) {} as WebDavTestResult.Passed).tested

        assertEquals(WebDavSaveResult.SaveFailed, service.save(login, tested))

        assertFalse(File(directory, WebDavCredentialStore.FILE_NAME).exists())
        assertFalse(File(directory, DestinationLinkStore.FILE_NAME).exists())
        assertEquals(0, connectedCalls)
    }

    @Test
    fun eachFailureArrivesWithItsStepAndCause() = runBlocking<Unit> {
        val cases =
            listOf<Triple<(RecordedRequest) -> FakeResponse?, WebDavTestStep, WebDavTestFailure>>(
                Triple(
                    { FakeResponse.status(401) },
                    WebDavTestStep.LIST,
                    WebDavTestFailure.CREDENTIAL_REJECTED,
                ),
                Triple(
                    { if (it.method == "PROPFIND") FakeResponse.status(403) else null },
                    WebDavTestStep.LIST,
                    WebDavTestFailure.FORBIDDEN,
                ),
                Triple(
                    { FakeResponse.status(429) },
                    WebDavTestStep.LIST,
                    WebDavTestFailure.RATE_LIMITED,
                ),
                Triple(
                    { if (it.method == "MKCOL") FakeResponse.status(409) else null },
                    WebDavTestStep.CREATE_FOLDER,
                    WebDavTestFailure.BASE_URL,
                ),
                Triple(
                    { if (it.method == "PUT") FakeResponse.status(400) else null },
                    WebDavTestStep.UPLOAD,
                    WebDavTestFailure.UNSUPPORTED,
                ),
                Triple(
                    { if (it.method == "MOVE") FakeResponse.status(400) else null },
                    WebDavTestStep.MOVE,
                    WebDavTestFailure.UNSUPPORTED,
                ),
                Triple(
                    { if (it.method == "PUT") FakeResponse.status(507) else null },
                    WebDavTestStep.UPLOAD,
                    WebDavTestFailure.NO_SPACE,
                ),
                Triple(
                    {
                        if (it.method == "PROPFIND") {
                            FakeResponse.status(
                                302,
                                mapOf("Location" to "https://otro.example.com/"),
                            )
                        } else {
                            null
                        }
                    },
                    WebDavTestStep.LIST,
                    WebDavTestFailure.REDIRECT,
                ),
            )
        for ((override, step, failure) in cases) {
            val (_, server) = server(override)
            assertEquals(
                "$step $failure",
                WebDavTestResult.Failed(step, failure),
                service(server).test(login, APP_NAME) {},
            )
        }
        nothingSaved()
    }

    @Test
    fun addressesAreCheckedWithoutAnyRequest() = runBlocking<Unit> {
        val (_, server) = server()
        val service = service(server)

        assertEquals(
            WebDavAddress.Rejected(WebDavAddressProblem.NOT_HTTPS),
            service.inspect("http://webdav.example.com/dav/"),
        )
        for (bad in listOf(
            "https://a:b@example.com/",
            "https://webdav.example.com/?x",
            "nada",
        )) {
            assertEquals(
                bad,
                WebDavAddress.Rejected(WebDavAddressProblem.INVALID),
                service.inspect(bad),
            )
        }
        assertEquals(
            WebDavAddress.Accepted("xn--tenda-zsa.example.com"),
            service.inspect("https://tíenda.example.com/dav"),
        )
        assertEquals(
            WebDavTestResult.AddressRejected(WebDavAddressProblem.NOT_HTTPS),
            service.test(
                WebDavLogin("http://webdav.example.com/", TEST_USER, TEST_PASSWORD),
                APP_NAME,
            ) {
            },
        )
        assertEquals(
            WebDavTestResult.MissingField,
            service.test(WebDavLogin(BASE_URL, TEST_USER, ""), APP_NAME) {},
        )
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun theCurrentDestinationIsReadForReconnectingWithoutThePassword() = runBlocking<Unit> {
        val (_, server) = server()
        val service = service(server)
        assertNull(service.current())

        row = stateRow("CONNECTED", "WEBDAV", TEST_USER, "id-1", "$BASE_URL$APP_NAME/")
        assertEquals(
            CurrentDestination(
                BackupDestinationKind.WEBDAV,
                "id-1",
                BASE_URL,
                TEST_USER,
                "webdav.example.com",
            ),
            service.current(),
        )

        row = stateRow("PERMISSION_LOST", "DOCUMENT_SLOTS", null, "slots", null)
        assertEquals(
            CurrentDestination(BackupDestinationKind.DOCUMENT_SLOTS, "slots", null, null, "Nube"),
            service.current(),
        )

        row = stateRow("NOT_CONNECTED", "WEBDAV", TEST_USER, "id-1", "$BASE_URL$APP_NAME/")
        assertNull(service.current())
    }

    private fun stateRow(
        link: String,
        kind: String?,
        account: String?,
        destinationId: String?,
        folder: String?,
    ) = BackupStateEntity(
        id = 1,
        changeSeq = 0,
        verifiedSeq = 0,
        firstUnsyncedAt = null,
        linkState = link,
        failureCode = null,
        consecutiveFailures = 0,
        lastFailureKind = null,
        invariantFailures = 0,
        lastAttemptAt = null,
        lastSuccessAt = null,
        lastUploadFinishedAt = null,
        lastStaleNoticeAt = null,
        lastDrillAt = null,
        lastDrillResult = null,
        lastFullVerifyAt = null,
        lastHumanCheckAt = null,
        humanCheckGeneration = null,
        destinationKind = kind,
        destinationAccount = account,
        destinationId = destinationId,
        destinationFolder = folder,
        headGeneration = 0,
        headDeviceId = null,
        headFileRef = null,
        shrinkAcceptedGeneration = null,
        pendingGeneration = null,
        pendingSeq = null,
        pendingSha256 = null,
        pendingSizeBytes = null,
        conflictKind = null,
        conflictGeneration = null,
        conflictDeviceId = null,
        conflictFileRef = null,
        copyGeneration = null,
        copyDeviceId = null,
        copyCreatedAt = null,
    )

    private companion object {
        const val BASE_URL = "https://webdav.example.com/dav/"
        const val APP_NAME = "TiendaDePrueba"
        const val NOW = 1_794_000_000_000L
    }
}
