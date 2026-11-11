package io.github.yesidrobayo.shopledger.ui.destination

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotFileStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotSetupFile
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotSetupLook

/** Estados de muestra de la pantalla, para las vistas previas y las capturas. */
internal object SlotSetupSamples {
    fun look(
        a: SlotFileStatus,
        b: SlotFileStatus,
        c: SlotFileStatus,
        otherDestination: Boolean = false,
        longName: String? = null,
    ) = SlotSetupLook(
        providerLabel = "Drive",
        files =
            listOf(
                SlotSetupFile(BackupSlot.A, a, longName),
                SlotSetupFile(BackupSlot.B, b, null),
                SlotSetupFile(BackupSlot.C, c, null),
            ),
        otherDestination = otherDestination,
        done = false,
    )

    val empty =
        SlotSetupUiState(
            look(SlotFileStatus.MISSING, SlotFileStatus.MISSING, SlotFileStatus.MISSING),
        )

    val halfway =
        SlotSetupUiState(
            look(SlotFileStatus.CREATED, SlotFileStatus.MISSING, SlotFileStatus.MISSING),
        )

    val outside = halfway.copy(notice = SlotSetupNotice.OutsideProviders)

    val notEmpty = halfway.copy(notice = SlotSetupNotice.NotEmpty)

    val reconnect =
        SlotSetupUiState(look(SlotFileStatus.READY, SlotFileStatus.MISSING, SlotFileStatus.READY))

    val ready =
        SlotSetupUiState(
            look(SlotFileStatus.CREATED, SlotFileStatus.CREATED, SlotFileStatus.CREATED),
            ready = true,
        )

    val otherDestination =
        SlotSetupUiState(
            look(
                SlotFileStatus.CREATED,
                SlotFileStatus.CREATED,
                SlotFileStatus.MISSING,
                otherDestination = true,
            ),
        )
}
