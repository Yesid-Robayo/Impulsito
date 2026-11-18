package io.github.yesidrobayo.shopledger.ui.phrase

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Las reglas de la pantalla de escuchar, sin micrófono ni Android: los intentos por servicio,
 * empezar, lo que se va entendiendo, terminar, el recorrido por varios servicios cuando fallan y
 * cancelar. Los textos y los paquetes son inventados, salvo los que se prueban primero.
 */
class ListeningMachineTest {
    private val noServices = ListeningMachine(ListenPlan.plansFor(onDevice = false, services = 0))
    private val oneService = ListeningMachine(ListenPlan.plansFor(onDevice = false, services = 1))
    private val threeServices =
        ListeningMachine(ListenPlan.plansFor(onDevice = false, services = 3))
    private val onDeviceAndOne =
        ListeningMachine(ListenPlan.plansFor(onDevice = true, services = 1))

    private fun offline(service: Int?) = ListenPlan(ListenMode.OFFLINE_FIRST, service)

    private fun online(service: Int?) = ListenPlan(ListenMode.ONLINE, service)

    private fun listen(plan: ListenPlan) = ListenCommand.Listen(plan)

    /** Pasa [events] por [machine] desde el comienzo y devuelve cada paso. */
    private fun run(machine: ListeningMachine, vararg events: ListenEvent): List<ListenStep> {
        var state: ListeningState = ListeningState.Idle
        return events.map { event ->
            machine.next(state, event).also { state = it.state }
        }
    }

    @Test
    fun thePlansTryTheOnDeviceEngineAndThenEachServiceOfflineAndOnline() {
        assertEquals(
            listOf(
                ListenPlan(ListenMode.ON_DEVICE),
                offline(0),
                online(0),
                offline(1),
                online(1),
            ),
            ListenPlan.plansFor(onDevice = true, services = 2),
        )
    }

    @Test
    fun withoutListedServicesTheSystemDefaultIsTriedBothWays() {
        assertEquals(
            listOf(offline(null), online(null)),
            ListenPlan.plansFor(onDevice = false, services = 0),
        )
    }

    @Test
    fun theUsualServicesGoFirstAndTheRestKeepTheSystemOrder() {
        val packages =
            listOf(
                "com.example.vendor.voice",
                "com.google.android.tts",
                "com.example.other.voice",
                "com.google.android.as",
                "com.google.android.googlequicksearchbox",
            )

        assertEquals(listOf(4, 3, 1, 0, 2), ListenPlan.serviceOrder(packages))
    }

    @Test
    fun startingListensWithTheFirstPlanAndWaitsForTheEngine() {
        val step = run(oneService, ListenEvent.Start).single()

        assertEquals(ListenStep(ListeningState.Starting(0), listen(offline(0))), step)
    }

    @Test
    fun whatIsUnderstoodSoFarIsShownAndTheFinalTextIsHeard() {
        val steps =
            run(
                oneService,
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
                oneService,
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
                oneService,
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
    fun anyFailureButSilenceMovesToTheNextTry() {
        val errors =
            listOf(
                ListenError.NETWORK,
                ListenError.SERVER,
                ListenError.LANGUAGE,
                ListenError.PERMISSION,
                ListenError.BUSY,
                ListenError.OTHER,
            )
        for (error in errors) {
            val step = run(oneService, ListenEvent.Start, ListenEvent.Error(error)).last()

            assertEquals(
                error.name,
                ListenStep(
                    ListeningState.Starting(1, micDenied = error == ListenError.PERMISSION),
                    listen(online(0)),
                ),
                step,
            )
        }
    }

    @Test
    fun theOnDeviceEngineGoesFirstAndFallsToTheService() {
        val commands =
            run(
                onDeviceAndOne,
                ListenEvent.Start,
                ListenEvent.Error(ListenError.LANGUAGE),
                ListenEvent.Error(ListenError.LANGUAGE),
            ).map { it.command }

        assertEquals(
            listOf(listen(ListenPlan(ListenMode.ON_DEVICE)), listen(offline(0)), listen(online(0))),
            commands,
        )
    }

    @Test
    fun withoutListedServicesTheDefaultGoesOnline() {
        val step =
            run(noServices, ListenEvent.Start, ListenEvent.Error(ListenError.LANGUAGE)).last()

        assertEquals(listen(online(null)), step.command)
    }

    @Test
    fun theFirstServiceWithoutMicrophoneAndTheSecondBrokenLeaveTheThirdToUnderstand() {
        val steps =
            run(
                threeServices,
                ListenEvent.Start,
                ListenEvent.Error(ListenError.PERMISSION),
                ListenEvent.Error(ListenError.PERMISSION),
                ListenEvent.Error(ListenError.OTHER),
                ListenEvent.Error(ListenError.OTHER),
                ListenEvent.Ready,
                ListenEvent.Final("vendí 3 panes"),
            )

        assertEquals(
            listOf(
                listen(offline(0)),
                listen(online(0)),
                listen(offline(1)),
                listen(online(1)),
                listen(offline(2)),
            ),
            steps.take(5).map { it.command },
        )
        assertEquals(ListeningState.Listening(4, "", micDenied = true), steps[5].state)
        assertEquals(
            ListenStep(ListeningState.Heard("vendí 3 panes"), ListenCommand.Release),
            steps[6],
        )
    }

    @Test
    fun whenEveryTryFailsItEndsWithTheLastError() {
        val step =
            run(
                oneService,
                ListenEvent.Start,
                ListenEvent.Error(ListenError.NETWORK),
                ListenEvent.Error(ListenError.OTHER),
            ).last()

        assertEquals(
            ListenStep(ListeningState.Failed(ListenError.OTHER), ListenCommand.Release),
            step,
        )
    }

    @Test
    fun whenEveryTryFailsAndOneHadNoMicrophoneTheEndSaysSo() {
        val step =
            run(
                threeServices,
                ListenEvent.Start,
                ListenEvent.Error(ListenError.OTHER),
                ListenEvent.Error(ListenError.OTHER),
                ListenEvent.Error(ListenError.PERMISSION),
                ListenEvent.Error(ListenError.PERMISSION),
                ListenEvent.Error(ListenError.LANGUAGE),
                ListenEvent.Error(ListenError.NETWORK),
            ).last()

        assertEquals(
            ListenStep(
                ListeningState.Failed(ListenError.NETWORK, micDenied = true),
                ListenCommand.Release,
            ),
            step,
        )
    }

    @Test
    fun silenceOffersToListenAgainWithTheSameTry() {
        val steps =
            run(
                oneService,
                ListenEvent.Start,
                ListenEvent.Error(ListenError.PERMISSION),
                ListenEvent.Error(ListenError.NO_SPEECH),
                ListenEvent.Start,
            )

        assertEquals(
            ListenStep(ListeningState.Silent(1, micDenied = true), ListenCommand.Release),
            steps[2],
        )
        assertEquals(
            ListenStep(ListeningState.Starting(1, micDenied = true), listen(online(0))),
            steps[3],
        )
    }

    @Test
    fun anEmptyFinalTextIsSilenceNotAPhrase() {
        val step = run(oneService, ListenEvent.Start, ListenEvent.Final("   ")).last()

        assertEquals(ListeningState.Silent(0), step.state)
    }

    @Test
    fun aFailureAfterSomethingWasUnderstoodKeepsIt() {
        val step =
            run(
                oneService,
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
    fun cancellingReleasesTheEngineAndLateNoticesChangeNothing() {
        val steps =
            run(
                oneService,
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
        val step = run(oneService, ListenEvent.Start, ListenEvent.Ready, ListenEvent.Start).last()

        assertEquals(ListenStep(ListeningState.Listening(0, "")), step)
    }

    @Test
    fun aVeryLongTextIsCut() {
        val step = run(oneService, ListenEvent.Start, ListenEvent.Final("a".repeat(600))).last()

        assertEquals(ListeningState.Heard("a".repeat(500)), step.state)
    }
}
