package io.github.yesidrobayo.shopledger.localization

import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogLevel
import io.github.yesidrobayo.shopledger.domain.port.LogLine
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Cuándo se lee, se guarda y se aplica el idioma, con Android reemplazado por un doble: en Android 8
 * a 12L manda el archivo, y desde Android 13 manda lo que diga Android.
 */
class AppLanguageTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val log = RecordingLog()
    private val platform = FakeLocalePlatform()
    private val directory by lazy { folder.newFolder("no_backup") }
    private var directoryRequests = 0
    private var readsOutsidePermit = 0
    private var failDirectory = false
    private val file by lazy {
        LanguagePreferenceFile(
            {
                directoryRequests += 1
                if (!platform.insidePermit) readsOutsidePermit += 1
                if (failDirectory) throw IOException("sin carpeta")
                directory
            },
            {},
        )
    }
    private var choicesSeen = 0
    private val language by lazy {
        AppLanguage(file, platform, log, dispatcher, dispatcher) { choicesSeen += 1 }
    }

    // Android 8 a 12L: el archivo manda.

    @Test
    fun aNewPhoneStartsFollowingThePhone() {
        platform.systemManages = false

        language.applyAtProcessStart()

        assertEquals(UiLanguage.System, language.language.value)
        assertEquals(emptyList<UiLanguage>(), platform.applied)
        assertEquals(emptyList<LogLine>(), log.lines)
    }

    @Test
    fun theSavedLanguageIsAppliedAtStartInsideTheDiskPermit() {
        platform.systemManages = false
        file.write(chosen("es"))
        readsOutsidePermit = 0

        language.applyAtProcessStart()

        assertEquals(chosen("es"), language.language.value)
        assertEquals(listOf(chosen("es")), platform.applied)
        assertTrue(directoryRequests > 0)
        assertEquals(0, readsOutsidePermit)
    }

    @Test
    fun savedSystemNeedsNoCallToAndroid() {
        platform.systemManages = false
        file.write(UiLanguage.System)

        language.applyAtProcessStart()

        assertEquals(UiLanguage.System, language.language.value)
        assertEquals(emptyList<UiLanguage>(), platform.applied)
    }

    @Test
    fun aDamagedFileFollowsThePhoneAndIsLogged() {
        platform.systemManages = false
        File(directory, LanguagePreferenceFile.FILE_NAME).writeText("no es un idioma")

        language.applyAtProcessStart()

        assertEquals(UiLanguage.System, language.language.value)
        assertEquals(emptyList<UiLanguage>(), platform.applied)
        assertEquals(listOf("El archivo de idioma no trae un idioma: sigue al teléfono"), log.texts)
    }

    @Test
    fun anUnreadableFileFollowsThePhoneAndLogsOnlyTheClass() {
        platform.systemManages = false
        failDirectory = true

        language.applyAtProcessStart()

        assertEquals(UiLanguage.System, language.language.value)
        assertEquals(
            listOf(
                "No se pudo leer el archivo de idioma: sigue al teléfono: error=java.io.IOException",
            ),
            log.texts,
        )
    }

    @Test
    fun choosingSavesBeforeApplying() = runTest(dispatcher) {
        platform.systemManages = false
        platform.onApply = { assertEquals(LanguageRead.Found(it), file.read()) }

        val result = language.choose(chosen("pt"))

        assertEquals(LanguageChoice.Applied, result)
        assertEquals(chosen("pt"), language.language.value)
        assertEquals(listOf(chosen("pt")), platform.applied)
        assertEquals(emptyList<UiLanguage>(), platform.systemSet)
    }

    @Test
    fun choosingThePhoneLanguageSavesSystem() = runTest(dispatcher) {
        platform.systemManages = false
        language.choose(chosen("es"))

        val result = language.choose(UiLanguage.System)

        assertEquals(LanguageChoice.Applied, result)
        assertEquals(LanguageRead.Found(UiLanguage.System), file.read())
        assertEquals(listOf(chosen("es"), UiLanguage.System), platform.applied)
    }

    @Test
    fun anAppliedChoiceRunsTheFollowUpAfterSaving() = runTest(dispatcher) {
        platform.systemManages = false
        language.choose(chosen("pt"))

        assertEquals(1, choicesSeen)
    }

    @Test
    fun aChoiceThatCannotBeSavedHasNoFollowUp() = runTest(dispatcher) {
        platform.systemManages = false
        blockWrites()

        language.choose(chosen("pt"))

        assertEquals(0, choicesSeen)
    }

    @Test
    fun aChoiceThatCannotBeSavedIsNotApplied() = runTest(dispatcher) {
        platform.systemManages = false
        file.write(chosen("es"))
        language.applyAtProcessStart()
        blockWrites()

        val result = language.choose(chosen("pt"))

        assertEquals(LanguageChoice.NotSaved, result)
        assertEquals(chosen("es"), language.language.value)
        assertEquals(listOf(chosen("es")), platform.applied)
        assertEquals(LanguageRead.Found(chosen("es")), file.read())
        assertEquals(listOf(writeFailed("CHOSEN")), log.texts)
    }

    @Test
    fun withoutSystemLanguagesSyncDoesNothing() = runTest(dispatcher) {
        platform.systemManages = false
        file.write(chosen("es"))
        platform.system = chosen("pt")

        language.syncWithSystem()

        assertEquals(LanguageRead.Found(chosen("es")), file.read())
        assertEquals(0, platform.systemReads)
    }

    // Android 13 o superior: lo que diga Android manda.

    @Test
    fun withSystemLanguagesTheStartReadsNothing() {
        platform.systemManages = true
        file.write(chosen("es"))
        directoryRequests = 0

        language.applyAtProcessStart()

        assertEquals(0, directoryRequests)
        assertEquals(emptyList<UiLanguage>(), platform.applied)
        assertEquals(0, platform.systemReads)
    }

    @Test
    fun theLanguageChosenInAndroidReplacesTheSavedOne() = runTest(dispatcher) {
        platform.systemManages = true
        file.write(chosen("es"))
        platform.system = chosen("pt")

        language.syncWithSystem()

        assertEquals(LanguageRead.Found(chosen("pt")), file.read())
        assertEquals(chosen("pt"), language.language.value)
        assertEquals(listOf("El idioma de los ajustes de Android reemplazó al guardado"), log.texts)
    }

    @Test
    fun goingBackToThePhoneLanguageInAndroidIsSaved() = runTest(dispatcher) {
        platform.systemManages = true
        file.write(chosen("es"))
        file.markHandedToSystem()
        platform.system = UiLanguage.System

        language.syncWithSystem()

        assertEquals(LanguageRead.Found(UiLanguage.System), file.read())
        assertEquals(UiLanguage.System, language.language.value)
    }

    @Test
    fun aNewPhoneWithSystemLanguagesKeepsNoFile() = runTest(dispatcher) {
        platform.systemManages = true
        platform.system = UiLanguage.System

        language.syncWithSystem()

        assertEquals(LanguageRead.Missing, file.read())
        assertEquals(UiLanguage.System, language.language.value)
        assertEquals(emptyList<LogLine>(), log.lines)
    }

    @Test
    fun aSyncWithNothingNewWritesNothing() = runTest(dispatcher) {
        platform.systemManages = true
        file.write(chosen("pt"))
        platform.system = chosen("pt")
        blockWrites()

        language.syncWithSystem()

        assertEquals(chosen("pt"), language.language.value)
        assertEquals(emptyList<LogLine>(), log.lines)
    }

    @Test
    fun aDamagedFileIsRepairedFromAndroid() = runTest(dispatcher) {
        platform.systemManages = true
        File(directory, LanguagePreferenceFile.FILE_NAME).writeText("??")
        platform.system = UiLanguage.System

        language.syncWithSystem()

        assertEquals(LanguageRead.Found(UiLanguage.System), file.read())
    }

    @Test
    fun aSyncThatCannotSaveKeepsAndroidsLanguage() = runTest(dispatcher) {
        platform.systemManages = true
        file.write(chosen("es"))
        platform.system = chosen("pt")
        blockWrites()

        language.syncWithSystem()

        assertEquals(chosen("pt"), language.language.value)
        assertEquals(listOf(writeFailed("CHOSEN")), log.texts)
    }

    @Test
    fun withSystemLanguagesChoosingGoesToAndroidAndToTheFile() = runTest(dispatcher) {
        platform.systemManages = true

        val result = language.choose(chosen("pt"))

        assertEquals(LanguageChoice.Applied, result)
        assertEquals(listOf(chosen("pt")), platform.systemSet)
        assertEquals(emptyList<UiLanguage>(), platform.applied)
        assertEquals(LanguageRead.Found(chosen("pt")), file.read())
        assertEquals(chosen("pt"), language.language.value)
    }

    // El paso a Android 13: una sola vez, solo si Android no tiene idioma.

    @Test
    fun theFirstSyncOnAndroid13HandsTheSavedLanguageToAndroid() = runTest(dispatcher) {
        platform.systemManages = true
        file.write(chosen("es"))
        platform.system = UiLanguage.System

        language.syncWithSystem()

        assertEquals(listOf(chosen("es")), platform.systemSet)
        assertEquals(chosen("es"), language.language.value)
        assertEquals(LanguageRead.Found(chosen("es")), file.read())
        assertTrue(file.handedToSystem)
        assertEquals(
            listOf("El idioma guardado antes de Android 13 pasó a los ajustes de Android"),
            log.texts,
        )
    }

    @Test
    fun afterTheHandoffAndroidRulesEvenWithoutALanguage() = runTest(dispatcher) {
        platform.systemManages = true
        file.write(chosen("es"))
        platform.system = UiLanguage.System
        language.syncWithSystem()
        // La persona vuelve al idioma del teléfono desde los ajustes de Android.
        platform.system = UiLanguage.System

        language.syncWithSystem()

        assertEquals(listOf(chosen("es")), platform.systemSet)
        assertEquals(LanguageRead.Found(UiLanguage.System), file.read())
        assertEquals(UiLanguage.System, language.language.value)
    }

    @Test
    fun aLanguageAlreadyInAndroidIsNotReplacedByTheFile() = runTest(dispatcher) {
        platform.systemManages = true
        file.write(chosen("es"))
        platform.system = chosen("pt")

        language.syncWithSystem()

        assertEquals(emptyList<UiLanguage>(), platform.systemSet)
        assertEquals(LanguageRead.Found(chosen("pt")), file.read())
        assertTrue(file.handedToSystem)
    }

    @Test
    fun withoutAChosenLanguageThereIsNothingToHand() = runTest(dispatcher) {
        platform.systemManages = true
        file.write(UiLanguage.System)
        platform.system = UiLanguage.System

        language.syncWithSystem()

        assertEquals(emptyList<UiLanguage>(), platform.systemSet)
        assertTrue(file.handedToSystem)
    }

    @Test
    fun beforeAndroid13NothingIsHanded() = runTest(dispatcher) {
        platform.systemManages = false
        file.write(chosen("es"))

        language.syncWithSystem()

        assertFalse(file.handedToSystem)
        assertEquals(emptyList<UiLanguage>(), platform.systemSet)
    }

    @Test
    fun aHandoffMarkThatCannotBeSavedIsLoggedAndTheSyncGoesOn() = runTest(dispatcher) {
        platform.systemManages = true
        file.write(chosen("es"))
        platform.system = UiLanguage.System
        // Una carpeta con contenido en el lugar de la marca impide crearla como archivo.
        File(directory, LanguagePreferenceFile.HANDED_TO_SYSTEM_NAME).apply { mkdirs() }
            .resolve("inside")
            .writeText("x")

        language.syncWithSystem()

        assertEquals(chosen("es"), language.language.value)
        assertEquals(
            listOf(
                "El idioma guardado antes de Android 13 pasó a los ajustes de Android",
                "No se pudo completar el paso del idioma a Android: se repite al arrancar: " +
                    "error=java.io.IOException",
            ),
            log.texts,
        )
    }

    @Test
    fun aFolderThatCannotBeReachedDoesNotStopTheSync() = runTest(dispatcher) {
        platform.systemManages = true
        platform.system = chosen("pt")
        failDirectory = true

        language.syncWithSystem()

        assertEquals(chosen("pt"), language.language.value)
        assertTrue(log.texts.isNotEmpty())
    }

    @Test
    fun withSystemLanguagesAChoiceThatCannotBeSavedStillApplies() = runTest(dispatcher) {
        platform.systemManages = true
        blockWrites()

        val result = language.choose(UiLanguage.System)

        assertEquals(LanguageChoice.Applied, result)
        assertEquals(listOf<UiLanguage>(UiLanguage.System), platform.systemSet)
        assertEquals(listOf(writeFailed("SYSTEM")), log.texts)
    }

    @Test
    fun noLogLineCarriesTheLanguageTag() = runTest(dispatcher) {
        platform.systemManages = true
        file.write(chosen("es-419"))
        platform.system = chosen("pt-BR")
        language.syncWithSystem()
        blockWrites()
        language.choose(chosen("es-419"))

        assertTrue(log.lines.isNotEmpty())
        log.texts.forEach { text ->
            assertFalse(text, text.contains("419"))
            assertFalse(text, text.contains("pt-BR"))
        }
    }

    // Una carpeta con contenido en el lugar del temporal impide escribirlo.
    private fun blockWrites() {
        val blocker = File(directory, LanguagePreferenceFile.TEMPORARY_NAME).apply { mkdirs() }
        File(blocker, "inside").writeText("x")
    }

    private fun chosen(tag: String): UiLanguage = requireNotNull(UiLanguage.of(tag))

    // Bloquear el temporal con una carpeta hace que abrirlo falle con FileNotFoundException.
    private fun writeFailed(kind: String) =
        "No se pudo guardar el idioma: idioma=$kind, error=java.io.FileNotFoundException"

    private class RecordingLog : EventLog {
        val lines = mutableListOf<LogLine>()
        val texts: List<String> get() = lines.map { it.text }

        override fun write(level: LogLevel, line: LogLine) {
            lines += line
        }
    }

    private class FakeLocalePlatform : LocalePlatform {
        var systemManages = false
        var system: UiLanguage = UiLanguage.System
        var systemReads = 0
        var insidePermit = false
        var onApply: (UiLanguage) -> Unit = {}
        val applied = mutableListOf<UiLanguage>()
        val systemSet = mutableListOf<UiLanguage>()

        override val systemManagesAppLanguage: Boolean get() = systemManages

        override fun systemAppLanguage(): UiLanguage {
            systemReads += 1
            return system
        }

        override fun setSystemAppLanguage(language: UiLanguage) {
            systemSet += language
            system = language
        }

        override fun applyToActivities(language: UiLanguage) {
            onApply(language)
            applied += language
        }

        override fun <T> withStartupDiskPermit(block: () -> T): T {
            insidePermit = true
            try {
                return block()
            } finally {
                insidePermit = false
            }
        }
    }
}
