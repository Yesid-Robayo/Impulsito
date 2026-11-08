package io.github.yesidrobayo.shopledger.ui.i18n

import io.github.yesidrobayo.shopledger.localization.LanguageChoice
import io.github.yesidrobayo.shopledger.localization.UiLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La pantalla de idioma sin Android: tocar un idioma lo aplica y avisa, uno ya elegido no hace
 * nada, un idioma que no se guardó se puede reintentar, y lo que cambia en Android se ve aquí.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LanguageSettingsViewModelTest {
    private val language = MutableStateFlow<UiLanguage>(UiLanguage.System)
    private val chosen = mutableListOf<UiLanguage>()
    private var result: LanguageChoice = LanguageChoice.Applied

    @Before
    fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetDispatcher() = Dispatchers.resetMain()

    private fun viewModel() = LanguageSettingsViewModel(
        language = language,
        choose = { wanted ->
            chosen += wanted
            if (result == LanguageChoice.Applied) language.value = wanted
            result
        },
        installed = listOf("en", "es", "pt"),
    )

    private fun tag(value: String) = requireNotNull(UiLanguage.of(value))

    @Test
    fun `a new phone follows the phone and lists the installed languages`() {
        val state = viewModel().uiState.value

        assertEquals(null, state.selected)
        assertEquals(listOf("en", "es", "pt"), state.installed)
        assertFalse(state.changing)
    }

    @Test
    fun `choosing a language applies it at once and says so`() {
        val model = viewModel()

        model.onAction(LanguageSettingsAction.Choose("pt"))

        assertEquals(listOf(tag("pt")), chosen)
        assertEquals("pt", model.uiState.value.selected)
        assertEquals(LanguageNotice.UPDATED, model.uiState.value.notice)
        model.onAction(LanguageSettingsAction.NoticeShown)
        assertEquals(null, model.uiState.value.notice)

        model.onAction(LanguageSettingsAction.Choose(null))
        assertEquals(listOf(tag("pt"), UiLanguage.System), chosen)
        assertEquals(null, model.uiState.value.selected)
    }

    @Test
    fun `touching the chosen language does nothing`() {
        language.value = tag("es")
        val model = viewModel()

        model.onAction(LanguageSettingsAction.Choose("es"))

        assertTrue(chosen.isEmpty())
        assertEquals(null, model.uiState.value.notice)
    }

    @Test
    fun `a language that could not be saved keeps the old one and can be retried`() {
        result = LanguageChoice.NotSaved
        val model = viewModel()

        model.onAction(LanguageSettingsAction.Choose("es"))
        assertEquals(null, model.uiState.value.selected)
        assertEquals(LanguageNotice.NOT_SAVED, model.uiState.value.notice)

        result = LanguageChoice.Applied
        model.onAction(LanguageSettingsAction.Retry)
        assertEquals(listOf(tag("es"), tag("es")), chosen)
        assertEquals("es", model.uiState.value.selected)
        assertEquals(LanguageNotice.UPDATED, model.uiState.value.notice)
    }

    @Test
    fun `a language changed in Android settings shows here`() {
        val model = viewModel()

        language.value = tag("es")

        assertEquals("es", model.uiState.value.selected)
        assertTrue(chosen.isEmpty())
    }

    @Test
    fun `retrying without a failed choice does nothing`() {
        val model = viewModel()
        model.onAction(LanguageSettingsAction.Retry)
        assertTrue(chosen.isEmpty())
    }
}
