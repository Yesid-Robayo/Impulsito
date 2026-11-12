package io.github.yesidrobayo.shopledger.ui.onboarding

import androidx.compose.runtime.Composable
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopySummary
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyOrigin
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlin.time.Instant

// Vistas previas del primer uso, con datos inventados: la bienvenida sin red, las copias
// encontradas y la protección sin destino.

internal val PREVIEW_ONBOARDING_ZONE = BusinessTimeZone.of("Etc/GMT+5")

internal val PREVIEW_FOUND_COPY = BackupCopySummary(
    key = "copia-1",
    createdAt = Instant.parse("2026-11-07T15:00:00Z"),
    sizeBytes = 2_412_000,
    origin = CopyOrigin.OTHER_PHONE,
    slot = null,
    records = 4_812,
    newest = true,
    pinned = false,
    withDifferences = false,
    restorable = true,
)

internal val PREVIEW_WELCOME = OnboardingUiState(
    step = OnboardingStepId.WELCOME,
    slotsLabel = "Drive",
    zone = PREVIEW_ONBOARDING_ZONE,
)

@ThemePreviews
@Composable
private fun WelcomeOfflinePreview() {
    PreviewTheme { WelcomeStep(PREVIEW_WELCOME.copy(offline = true), PREVIEW_ONBOARDING_ZONE, {}) }
}

@ThemePreviews
@Composable
private fun FoundCopiesPreview() {
    PreviewTheme {
        WelcomeStep(
            PREVIEW_WELCOME.copy(found = FoundCopies.Found(listOf(PREVIEW_FOUND_COPY))),
            PREVIEW_ONBOARDING_ZONE,
            {},
        )
    }
}

@ThemePreviews
@Composable
private fun ProtectionPreview() {
    PreviewTheme { ProtectionStep(PREVIEW_WELCOME.copy(step = OnboardingStepId.PROTECTION), {}) }
}

@ThemePreviews
@Composable
private fun ProtectionWithLockPreview() {
    PreviewTheme {
        ProtectionStep(
            PREVIEW_WELCOME.copy(step = OnboardingStepId.PROTECTION, lockOffered = true),
            {},
        )
    }
}
