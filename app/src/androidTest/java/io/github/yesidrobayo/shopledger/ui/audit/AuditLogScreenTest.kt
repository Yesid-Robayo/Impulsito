package io.github.yesidrobayo.shopledger.ui.audit

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEvent
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEventType
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditResult
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Registro de auditoría` en el teléfono: dice qué muestra y qué no, nombra cada evento con su
 * resultado en palabra, explica el vacío y el filtro sin fallos, y una lectura fallida ofrece
 * `Reintentar`. No hay ninguna acción que edite o borre.
 */
@RunWith(AndroidJUnit4::class)
class AuditLogScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<AuditLogAction>()
    private val zone = checkNotNull(BusinessTimeZone.of("UTC"))
    private val at = Instant.parse("2026-11-12T15:00:00Z")

    private fun text(id: Int) = resources.getString(id)

    private fun show(state: AuditLogUiState) {
        compose.setContent { PreviewTheme { AuditLogScreen(state) { actions += it } } }
    }

    private fun ready(filter: AuditFilter, events: List<AuditEvent>) = AuditLogUiState.Ready(
        filter = filter,
        days =
            if (events.isEmpty()) {
                emptyList()
            } else {
                listOf(AuditDay(BusinessDate.of(at, zone), events))
            },
        zone = zone,
        canLoadMore = false,
    )

    @Test
    fun eachEventShowsWhatHappenedAndHowItEndedInWords() {
        show(
            ready(
                AuditFilter.ALL,
                listOf(
                    AuditEvent(2, at, AuditEventType.AUTH_FAILED, AuditResult.FAILURE),
                    AuditEvent(1, at, AuditEventType.EXPORT_CSV, AuditResult.SUCCESS),
                ),
            ),
        )

        compose.onNodeWithText(text(R.string.audit_intro)).assertExists()
        compose.onNodeWithText(text(R.string.audit_event_auth_failed)).assertExists()
        compose.onNodeWithText(text(R.string.audit_result_failure)).assertExists()
        compose.onNodeWithText(text(R.string.audit_event_export_csv)).assertExists()
        compose.onNodeWithText(text(R.string.audit_result_success)).assertExists()
    }

    @Test
    fun withoutEventsItSaysWhatWillAppear() {
        show(ready(AuditFilter.ALL, emptyList()))

        compose.onNodeWithText(text(R.string.audit_empty_title)).assertExists()
        compose.onNodeWithText(text(R.string.audit_empty_body)).assertExists()
        compose.onNodeWithText(text(R.string.audit_filter_failures)).performClick()

        assertEquals(listOf<AuditLogAction>(AuditLogAction.Filter(AuditFilter.FAILURES)), actions)
    }

    @Test
    fun withoutFailuresItOffersToClearTheFilter() {
        show(ready(AuditFilter.FAILURES, emptyList()))

        compose.onNodeWithText(text(R.string.audit_empty_failures)).assertExists()
        compose.onNodeWithText(text(R.string.action_clear_filters)).performClick()

        assertTrue(AuditLogAction.Filter(AuditFilter.ALL) in actions)
    }

    @Test
    fun aFailedReadOffersRetry() {
        show(AuditLogUiState.ReadFailed)

        compose.onNodeWithText(text(R.string.state_error_read)).assertExists()
        compose.onNodeWithText(text(R.string.action_retry)).performClick()

        assertEquals(listOf<AuditLogAction>(AuditLogAction.Retry), actions)
    }
}
