package io.github.yesidrobayo.shopledger.ui.phrase

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Las reglas de la pantalla de escuchar, sin micrófono ni Android: empezar, lo que se va
 * entendiendo, terminar, cada fallo con su reintento por la red y cancelar. Los textos son frases
 * inventadas.
 */
class ListeningMachineTest {
    private val withOnDevice = ListeningMachine(ListenPlan.plansFor(onDevice = true))
    private val withoutOnDevice = ListeningMachine(ListenPlan.plansFor(onDevice = false))

    /** Pasa [events] por [machine] desde el comienzo y devuelve cada paso. */
    private fun run(machine: ListeningMachine, vararg events: ListenEvent): List<ListenStep> {
        var state: ListeningState = ListeningState.Idle
        return events.map { event ->
            machine.next(state, event).also { state = it.state }
        }
    }

    @Test
    fun thePlansPutTheOnDeviceEngineFirstOnlyWhenThePhoneHasIt() {
        assertEquals(
            listOf(ListenPlan.ON_DEVICE, ListenPlan.OFFLINE_FIRST, ListenPlan.ONLINE),
            ListenPlan.plansFor(onDevice = true),
        )
        assertEquals(
            listOf(ListenPlan.OFFLINE_FIRST, ListenPlan.ONLINE),
            ListenPlan.plansFor(onDevice = false),
        )
    }

    @Test
    fun startingListensWithTheFirstPlanAndWaitsForTheEngine() {
        val step = run(withoutOnDevice, ListenEvent.Start).single()

        assertEquals(
            ListenStep(ListeningState.Starting(0), ListenCommand.Listen(ListenPlan.OFFLINE_FIRST)),
            step,
        )
    }

    @Test
    fun whatIsUnderstoodSoFarIsShownAndTheFinalTextIsHeard() {
        val steps =
            run(
                withoutOnDevice,
                ListenEvent.Start,
                ListenEvent.Ready,
                ListenEvent.Partial("vendí 3"),
                ListenEvent.Partial("  vendí 3 panes  "),
                ListenEvent.Final("vendí 3 panes en efectivo"),
            )

        assertEquals(ListeningState.Listening(0, ""), steps[1].state)
        assertEquals(ListeningState.Listening(0, "vendí 3"), steps[2].state)
        assertEquals(ListeningState.Listening(0, "vendí 3 panes"), steps[3].state)
        assertEquals(
            ListenStep(ListeningState.Heard("vendí 3 panes en efectivo"), ListenCommand.Release),
            steps[4],
        )
    }

    @Test
    fun anEmptyPartialDoesNotEraseWhatWasUnderstood() {
        val steps =
            run(
                withoutOnDevice,
                ListenEvent.Start,
                ListenEvent.Partial("pagué la luz"),
                ListenEvent.Partial(" "),
            )

        assertEquals(ListeningState.Listening(0, "pagué la luz"), steps.last().state)
    }

    @Test
    fun doneStopsTheEngineAndKeepsWhatWasSaid() {
        val steps =
            run(
                withoutOnDevice,
                ListenEvent.Start,
                ListenEvent.Ready,
                ListenEvent.Partial("se dañaron 2 panes"),
                ListenEvent.Finish,
                ListenEvent.Final(""),
            )

        assertEquals(
            ListenStep(ListeningState.Finishing(0, "se dañaron 2 panes"), ListenCommand.Stop),
            steps[3],
        )
        assertEquals(ListeningState.Heard("se dañaron 2 panes"), steps[4].state)
    }

    @Test
    fun aNetworkErrorWhilePreferringOfflineRetriesOnceOnline() {
        val steps =
            run(
                withoutOnDevice,
                ListenEvent.Start,
                ListenEvent.Ready,
                ListenEvent.Error(ListenError.NETWORK),
                ListenEvent.Ready,
                ListenEvent.Final("compré 10 kilos de harina"),
            )

        assertEquals(
            ListenStep(ListeningState.Starting(1), ListenCommand.Listen(ListenPlan.ONLINE)),
            steps[2],
        )
        assertEquals(ListeningState.Heard("compré 10 kilos de harina"), steps[4].state)
    }

    @Test
    fun languageAndServerErrorsAlsoRetryOnline() {
        for (error in listOf(ListenError.LANGUAGE, ListenError.SERVER)) {
            val step = run(withoutOnDevice, ListenEvent.Start, ListenEvent.Error(error)).last()

            assertEquals(error.name, ListenCommand.Listen(ListenPlan.ONLINE), step.command)
        }
    }

    @Test
    fun whenTheOnlineTryAlsoFailsItEndsOnTheKeyboard() {
        val step =
            run(
                withoutOnDevice,
                ListenEvent.Start,
                ListenEvent.Error(ListenError.NETWORK),
                ListenEvent.Error(ListenError.NETWORK),
            ).last()

        assertEquals(
            ListenStep(ListeningState.Failed(ListenError.NETWORK), ListenCommand.Release),
            step,
        )
    }

    @Test
    fun otherErrorsWhilePreferringOfflineDoNotRetry() {
        for (error in listOf(ListenError.BUSY, ListenError.OTHER)) {
            val step = run(withoutOnDevice, ListenEvent.Start, ListenEvent.Error(error)).last()

            assertEquals(error.name, ListeningState.Failed(error), step.state)
        }
    }

    @Test
    fun theOnDeviceEngineFallsBackToTheUsualOneOnAnyFailure() {
        for (error in listOf(ListenError.LANGUAGE, ListenError.BUSY, ListenError.OTHER)) {
            val steps =
                run(
                    withOnDevice,
                    ListenEvent.Start,
                    ListenEvent.Error(error),
                    ListenEvent.Error(ListenError.NETWORK),
                )

            val commands = steps.map { it.command }

            assertEquals(
                error.name,
                listOf(
                    ListenCommand.Listen(ListenPlan.ON_DEVICE),
                    ListenCommand.Listen(ListenPlan.OFFLINE_FIRST),
                    ListenCommand.Listen(ListenPlan.ONLINE),
                ),
                commands,
            )
        }
    }

    @Test
    fun silenceOffersToListenAgainWithTheSameTry() {
        val steps =
            run(
                withOnDevice,
                ListenEvent.Start,
                ListenEvent.Error(ListenError.LANGUAGE),
                ListenEvent.Error(ListenError.NO_SPEECH),
                ListenEvent.Start,
            )

        assertEquals(ListenStep(ListeningState.Silent(1), ListenCommand.Release), steps[2])
        assertEquals(
            ListenStep(ListeningState.Starting(1), ListenCommand.Listen(ListenPlan.OFFLINE_FIRST)),
            steps[3],
        )
    }

    @Test
    fun anEmptyFinalTextIsSilenceNotAPhrase() {
        val step = run(withoutOnDevice, ListenEvent.Start, ListenEvent.Final("   ")).last()

        assertEquals(ListeningState.Silent(0), step.state)
    }

    @Test
    fun aFailureAfterSomethingWasUnderstoodKeepsIt() {
        val step =
            run(
                withoutOnDevice,
                ListenEvent.Start,
                ListenEvent.Partial("vendí 2 jugos"),
                ListenEvent.Error(ListenError.SERVER),
            ).last()

        assertEquals(
            ListenStep(ListeningState.Heard("vendí 2 jugos"), ListenCommand.Release),
            step,
        )
    }

    @Test
    fun withoutPermissionItNeverRetries() {
        val step =
            run(withOnDevice, ListenEvent.Start, ListenEvent.Error(ListenError.PERMISSION)).last()

        assertEquals(
            ListenStep(ListeningState.Failed(ListenError.PERMISSION), ListenCommand.Release),
            step,
        )
    }

    @Test
    fun cancellingReleasesTheEngineAndLateNoticesChangeNothing() {
        val steps =
            run(
                withoutOnDevice,
                ListenEvent.Start,
                ListenEvent.Partial("vendí"),
                ListenEvent.Cancel,
                ListenEvent.Final("vendí 3 panes"),
                ListenEvent.Start,
            )

        assertEquals(ListenStep(ListeningState.Cancelled, ListenCommand.Release), steps[2])
        assertEquals(ListenStep(ListeningState.Cancelled), steps[3])
        assertEquals(ListenStep(ListeningState.Cancelled), steps[4])
    }

    @Test
    fun aSecondStartWhileListeningDoesNotOpenAnotherTry() {
        val step =
            run(withoutOnDevice, ListenEvent.Start, ListenEvent.Ready, ListenEvent.Start).last()

        assertEquals(ListenStep(ListeningState.Listening(0, "")), step)
    }

    @Test
    fun aVeryLongTextIsCut() {
        val step =
            run(withoutOnDevice, ListenEvent.Start, ListenEvent.Final("a".repeat(600))).last()

        assertEquals(ListeningState.Heard("a".repeat(500)), step.state)
    }
}
