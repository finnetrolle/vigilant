package io.vigilant.windowing

import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Public generic-seam tests for protocol- and detector-neutral windowed inspection. */
class WindowedInspectionExecutorTest {
    /** A synthetic non-PII detector discovers one boundary finding in original UTF-8 coordinates. */
    @Test
    fun `synthetic detector discovers a cross-window finding owned by its starting core`() {
        val windows = ArrayList<String>()
        val contract = SyntheticContract(windows)
        val cpuExecutor = Executors.newSingleThreadExecutor()

        try {
            val result =
                WindowedInspectionExecutor(cpuExecutor)
                    .inspect(
                        InspectableTextFragment("aaaaaaaXYZzzzzzz", FragmentReference("synthetic-boundary")),
                        SyntheticInput("XYZ"),
                        contract,
                    ).get(5, TimeUnit.SECONDS)
            val success = assertIs<WindowedInspectionResult.Success<SyntheticFinding>>(result)

            assertEquals(FragmentReference("synthetic-boundary"), success.provenance)
            assertEquals(
                listOf(GlobalFinding(SyntheticFinding("marker", 1), 7, 10)),
                success.findings,
            )
            assertEquals(listOf("aaaaaaaXYZ", "aXYZzzzzzz"), windows)
        } finally {
            cpuExecutor.shutdownNow()
        }
    }

    /** Semantic duplicates collapse before detector-controlled ordering and immutable publication. */
    @Test
    fun `synthetic aggregate is deduplicated ordered and immutable`() {
        val contract =
            object : WindowedDetectorContract<Unit, SyntheticFinding, String> {
                /** Small direct-call capability for the synthetic aggregate. */
                override val capability: WindowedCapability = WindowedCapability("aggregate@1", 32, 4)

                /** Returns deliberately duplicated and non-canonical local findings. */
                override fun detect(
                    window: String,
                    input: Unit,
                ): List<LocalFinding<SyntheticFinding>> =
                    listOf(
                        LocalFinding(SyntheticFinding("later-rank", 2), 0, 1),
                        LocalFinding(SyntheticFinding("first-rank", 1), 3, 4),
                        LocalFinding(SyntheticFinding("later-rank", 2), 0, 1),
                    )

                /** Uses label and translated span as semantic duplicate identity. */
                override fun semanticIdentity(finding: GlobalFinding<SyntheticFinding>): String =
                    "${finding.value.label}:${finding.startUtf8}:${finding.endUtf8}"

                /** Requires all synthetic metadata to agree for one identity. */
                override fun hasEquivalentMetadata(
                    first: SyntheticFinding,
                    second: SyntheticFinding,
                ): Boolean = first == second

                /** Orders by detector-owned rank instead of source position. */
                override val canonicalComparator: Comparator<GlobalFinding<SyntheticFinding>> =
                    compareBy({ finding -> finding.value.rank }, GlobalFinding<SyntheticFinding>::startUtf8)
            }
        val cpuExecutor = Executors.newSingleThreadExecutor()

        try {
            val success =
                assertIs<WindowedInspectionResult.Success<SyntheticFinding>>(
                    WindowedInspectionExecutor(cpuExecutor)
                        .inspect(
                            InspectableTextFragment("abcd", FragmentReference("synthetic-aggregate")),
                            Unit,
                            contract,
                        ).get(5, TimeUnit.SECONDS),
                )

            assertEquals(
                listOf(
                    GlobalFinding(SyntheticFinding("first-rank", 1), 3, 4),
                    GlobalFinding(SyntheticFinding("later-rank", 2), 0, 1),
                ),
                success.findings,
            )
            assertFailsWith<UnsupportedOperationException> {
                @Suppress("UNCHECKED_CAST")
                (success.findings as MutableList<GlobalFinding<SyntheticFinding>>).clear()
            }
        } finally {
            cpuExecutor.shutdownNow()
        }
    }

    /** Every generic validation and detector failure is typed, safe, and contains no partial aggregate. */
    @Test
    @Suppress("LongMethod")
    fun `generic error matrix returns exact safe codes without partial findings`() {
        val cases =
            listOf(
                ErrorCase(
                    WindowedCapability("", 16, 4),
                    "text",
                    detector = { _, _ -> emptyList() },
                    WindowedInspectionErrorCode.INVALID_CAPABILITY,
                ),
                ErrorCase(
                    WindowedCapability("invalid-zero", 0, 1),
                    "text",
                    detector = { _, _ -> emptyList() },
                    WindowedInspectionErrorCode.INVALID_CAPABILITY,
                ),
                ErrorCase(
                    WindowedCapability("invalid-negative", -1, 1),
                    "text",
                    detector = { _, _ -> emptyList() },
                    WindowedInspectionErrorCode.INVALID_CAPABILITY,
                ),
                ErrorCase(
                    WindowedCapability("invalid-evidence-negative", 16, -1),
                    "text",
                    detector = { _, _ -> emptyList() },
                    WindowedInspectionErrorCode.INVALID_CAPABILITY,
                ),
                ErrorCase(
                    WindowedCapability("invalid-evidence-zero", 16, 0),
                    "text",
                    detector = { _, _ -> emptyList() },
                    WindowedInspectionErrorCode.INVALID_CAPABILITY,
                ),
                ErrorCase(
                    WindowedCapability("invalid-evidence-over-limit", 16, 17),
                    "text",
                    detector = { _, _ -> emptyList() },
                    WindowedInspectionErrorCode.INVALID_CAPABILITY,
                ),
                ErrorCase(
                    WindowedCapability("invalid-no-core-progress", 16, 8),
                    "text",
                    detector = { _, _ -> emptyList() },
                    WindowedInspectionErrorCode.INVALID_CAPABILITY,
                ),
                ErrorCase(
                    WindowedCapability("unbounded", 4, null),
                    "12345",
                    detector = { _, _ -> emptyList() },
                    WindowedInspectionErrorCode.WINDOWING_UNSUPPORTED,
                ),
                ErrorCase(
                    WindowedCapability("invalid-negative-span", 16, 4),
                    "text",
                    detector = { _, _ -> listOf(LocalFinding(SyntheticFinding("secret", 1), -1, 1)) },
                    WindowedInspectionErrorCode.INVALID_DETECTOR_RESULT,
                ),
                ErrorCase(
                    WindowedCapability("invalid-empty-span", 16, 4),
                    "text",
                    detector = { _, _ -> listOf(LocalFinding(SyntheticFinding("secret", 1), 1, 1)) },
                    WindowedInspectionErrorCode.INVALID_DETECTOR_RESULT,
                ),
                ErrorCase(
                    WindowedCapability("invalid-reversed-span", 16, 4),
                    "text",
                    detector = { _, _ -> listOf(LocalFinding(SyntheticFinding("secret", 1), 2, 1)) },
                    WindowedInspectionErrorCode.INVALID_DETECTOR_RESULT,
                ),
                ErrorCase(
                    WindowedCapability("invalid-overrun", 16, 4),
                    "text",
                    detector = { _, _ -> listOf(LocalFinding(SyntheticFinding("secret", 1), 1, 5)) },
                    WindowedInspectionErrorCode.INVALID_DETECTOR_RESULT,
                ),
                ErrorCase(
                    WindowedCapability("invalid-boundary", 16, 4),
                    "€",
                    detector = { _, _ -> listOf(LocalFinding(SyntheticFinding("secret", 1), 1, 2)) },
                    WindowedInspectionErrorCode.INVALID_DETECTOR_RESULT,
                ),
                ErrorCase(
                    WindowedCapability("invalid-unicode", 16, 4),
                    "\ud800",
                    detector = { _, _ -> emptyList() },
                    WindowedInspectionErrorCode.INVALID_FRAGMENT,
                ),
                ErrorCase(
                    WindowedCapability("detector-error", 16, 4),
                    "text",
                    detector = { _, _ -> error("secret detector detail") },
                    WindowedInspectionErrorCode.DETECTOR_ERROR,
                ),
            )

        cases.forEach { case ->
            val invocations = AtomicInteger()
            val contract = errorContract(case, invocations)
            val cpuExecutor = Executors.newSingleThreadExecutor()
            try {
                val result =
                    WindowedInspectionExecutor(cpuExecutor)
                        .inspect(
                            InspectableTextFragment(case.fragment, FragmentReference("secret-locator")),
                            Unit,
                            contract,
                        ).get(5, TimeUnit.SECONDS)

                assertEquals(WindowedInspectionResult.Error(case.expectedCode), result, case.capability.version)
                assertTrue("secret" !in result.toString())
                if (
                    case.expectedCode == WindowedInspectionErrorCode.INVALID_CAPABILITY ||
                    case.expectedCode == WindowedInspectionErrorCode.WINDOWING_UNSUPPORTED ||
                    case.expectedCode == WindowedInspectionErrorCode.INVALID_FRAGMENT
                ) {
                    assertEquals(0, invocations.get(), case.capability.version)
                }
            } finally {
                cpuExecutor.shutdownNow()
            }
        }
    }

    /** A conflicting duplicate and a later detector failure stop with no partial aggregate or further call. */
    @Test
    fun `generic conflict and later detector error stop without partial findings`() {
        val conflictContract =
            functionalContract(
                WindowedCapability("conflict", 16, 4),
            ) { _, _ ->
                listOf(
                    LocalFinding(SyntheticFinding("same", 1), 0, 1),
                    LocalFinding(SyntheticFinding("same", 2), 0, 1),
                )
            }
        val conflictExecutor = Executors.newSingleThreadExecutor()
        try {
            val conflict =
                WindowedInspectionExecutor(conflictExecutor)
                    .inspect(
                        InspectableTextFragment("text", FragmentReference("conflict")),
                        Unit,
                        conflictContract,
                    ).get(5, TimeUnit.SECONDS)
            assertEquals(
                WindowedInspectionResult.Error(WindowedInspectionErrorCode.INCONSISTENT_WINDOW_RESULT),
                conflict,
            )
        } finally {
            conflictExecutor.shutdownNow()
        }

        val invocations = AtomicInteger()
        val failureContract =
            functionalContract(
                WindowedCapability("later-error", 12, 3),
            ) { _, _ ->
                if (invocations.incrementAndGet() == 2) {
                    error("secret detector detail")
                }
                listOf(LocalFinding(SyntheticFinding("partial", 1), 0, 1))
            }
        val failureExecutor = Executors.newSingleThreadExecutor()
        try {
            val failure =
                WindowedInspectionExecutor(failureExecutor)
                    .inspect(
                        InspectableTextFragment("abcdefghijklmnopqrstuvwx", FragmentReference("later-error")),
                        Unit,
                        failureContract,
                    ).get(5, TimeUnit.SECONDS)

            assertEquals(WindowedInspectionResult.Error(WindowedInspectionErrorCode.DETECTOR_ERROR), failure)
            assertEquals(2, invocations.get())
        } finally {
            failureExecutor.shutdownNow()
        }
    }

    /** Cancellation interrupts the active detector and prevents every subsequent window invocation. */
    @Test
    fun `generic future cancellation stops active detector before another window`() {
        val started = CountDownLatch(1)
        val invocations = AtomicInteger()
        val contract =
            functionalContract(
                WindowedCapability("cancel", 12, 3),
            ) { _, _ ->
                invocations.incrementAndGet()
                started.countDown()
                while (!Thread.currentThread().isInterrupted) {
                    Thread.onSpinWait()
                }
                throw CancellationException()
            }
        val cpuExecutor = Executors.newSingleThreadExecutor()

        try {
            val future =
                WindowedInspectionExecutor(cpuExecutor)
                    .inspect(
                        InspectableTextFragment("abcdefghijklmnopqrstuvwx", FragmentReference("cancel")),
                        Unit,
                        contract,
                    )
            assertTrue(started.await(5, TimeUnit.SECONDS))

            assertTrue(future.cancel(true))
            assertFailsWith<CancellationException> { future.get() }
            assertEquals(1, invocations.get())
        } finally {
            cpuExecutor.shutdownNow()
        }
    }

    /** All windows run sequentially inside the one task accepted by the supplied bounded CPU executor. */
    @Test
    fun `generic execution stays sequential bounded and on the supplied CPU executor`() {
        val submittedTasks = AtomicInteger()
        val threadNumber = AtomicInteger()
        val activeCalls = AtomicInteger()
        val maximumActiveCalls = AtomicInteger()
        val windowSizes = ArrayList<Int>()
        val detectorThreads = ArrayList<String>()
        val seenInputs = ArrayList<SyntheticInput>()
        val input = SyntheticInput("unused")
        val cpuExecutor =
            object : ThreadPoolExecutor(
                2,
                2,
                0L,
                TimeUnit.MILLISECONDS,
                LinkedBlockingQueue(2),
                { task -> Thread(task, "bounded-generic-${threadNumber.getAndIncrement()}") },
                AbortPolicy(),
            ) {
                /** Records every task accepted by the public bounded-executor seam. */
                override fun execute(command: Runnable) {
                    submittedTasks.incrementAndGet()
                    super.execute(command)
                }
            }
        val contract =
            object : WindowedDetectorContract<SyntheticInput, SyntheticFinding, String> {
                /** Capability forcing multiple bounded detector windows. */
                override val capability: WindowedCapability = WindowedCapability("resources@1", 12, 3)

                /** Records detector execution state and returns no findings. */
                override fun detect(
                    window: String,
                    input: SyntheticInput,
                ): List<LocalFinding<SyntheticFinding>> {
                    val active = activeCalls.incrementAndGet()
                    maximumActiveCalls.accumulateAndGet(active, ::maxOf)
                    return try {
                        windowSizes += window.toByteArray(StandardCharsets.UTF_8).size
                        detectorThreads += Thread.currentThread().name
                        seenInputs += input
                        emptyList()
                    } finally {
                        activeCalls.decrementAndGet()
                    }
                }

                /** Uses label and translated span as semantic identity. */
                override fun semanticIdentity(finding: GlobalFinding<SyntheticFinding>): String =
                    "${finding.value.label}:${finding.startUtf8}:${finding.endUtf8}"

                /** Requires all synthetic metadata to agree for one identity. */
                override fun hasEquivalentMetadata(
                    first: SyntheticFinding,
                    second: SyntheticFinding,
                ): Boolean = first == second

                /** Orders synthetic findings by rank before position. */
                override val canonicalComparator: Comparator<GlobalFinding<SyntheticFinding>> =
                    compareBy({ finding -> finding.value.rank }, GlobalFinding<SyntheticFinding>::startUtf8)
            }

        try {
            val success =
                assertIs<WindowedInspectionResult.Success<SyntheticFinding>>(
                    WindowedInspectionExecutor(cpuExecutor)
                        .inspect(
                            InspectableTextFragment("abcdefghijklmnopqrstuvwx", FragmentReference("resources")),
                            input,
                            contract,
                        ).get(5, TimeUnit.SECONDS),
                )

            assertEquals(emptyList(), success.findings)
            assertEquals(1, submittedTasks.get())
            assertTrue(windowSizes.size > 1)
            assertTrue(windowSizes.all { size -> size <= contract.capability.maxWindowUtf8Bytes })
            assertEquals(1, maximumActiveCalls.get())
            assertEquals(setOf("bounded-generic-0"), detectorThreads.toSet())
            assertTrue(seenInputs.all { seen -> seen === input })
        } finally {
            cpuExecutor.shutdownNow()
        }
    }

    /** Immutable synthetic input supplied independently of the detector implementation. */
    private data class SyntheticInput(
        val marker: String,
    )

    /** Immutable synthetic finding metadata without local or global ownership state. */
    private data class SyntheticFinding(
        val label: String,
        val rank: Int,
    )

    /** One synthetic safe-error case and its detector behavior. */
    private data class ErrorCase(
        val capability: WindowedCapability,
        val fragment: String,
        val detector: (String, Unit) -> List<LocalFinding<SyntheticFinding>>,
        val expectedCode: WindowedInspectionErrorCode,
    )

    /** Synthetic non-PII contract exercising the public generic detector boundary. */
    private class SyntheticContract(
        private val windows: MutableList<String>,
    ) : WindowedDetectorContract<SyntheticInput, SyntheticFinding, String> {
        /** Window plan with two bytes of detector context on each core side. */
        override val capability: WindowedCapability = WindowedCapability("synthetic@1", 12, 3)

        /** Finds every complete marker visible in one detector window. */
        override fun detect(
            window: String,
            input: SyntheticInput,
        ): List<LocalFinding<SyntheticFinding>> {
            windows += window
            val start = window.indexOf(input.marker)
            if (start < 0) {
                return emptyList()
            }
            return listOf(
                LocalFinding(
                    SyntheticFinding("marker", 1),
                    utf8Size(window.substring(0, start)),
                    utf8Size(window.substring(0, start + input.marker.length)),
                ),
            )
        }

        /** Builds stable duplicate identity from detector metadata and translated span. */
        override fun semanticIdentity(finding: GlobalFinding<SyntheticFinding>): String =
            "${finding.value.label}:${finding.startUtf8}:${finding.endUtf8}"

        /** Compares synthetic metadata excluded from semantic identity. */
        override fun hasEquivalentMetadata(
            first: SyntheticFinding,
            second: SyntheticFinding,
        ): Boolean = first == second

        /** Orders synthetic results by detector-owned rank before original position. */
        override val canonicalComparator: Comparator<GlobalFinding<SyntheticFinding>> =
            compareBy({ finding -> finding.value.rank }, GlobalFinding<SyntheticFinding>::startUtf8)
    }

    /** Creates a synthetic contract and records every detector invocation. */
    private fun errorContract(
        case: ErrorCase,
        invocations: AtomicInteger,
    ): WindowedDetectorContract<Unit, SyntheticFinding, String> =
        functionalContract(case.capability) { window, input ->
            invocations.incrementAndGet()
            case.detector(window, input)
        }

    /** Creates a complete synthetic contract around one detector function. */
    private fun functionalContract(
        capability: WindowedCapability,
        detector: (String, Unit) -> List<LocalFinding<SyntheticFinding>>,
    ): WindowedDetectorContract<Unit, SyntheticFinding, String> =
        object : WindowedDetectorContract<Unit, SyntheticFinding, String> {
            /** Capability selected by the test scenario. */
            override val capability: WindowedCapability = capability

            /** Delegates detection to the scenario function. */
            override fun detect(
                window: String,
                input: Unit,
            ): List<LocalFinding<SyntheticFinding>> = detector(window, input)

            /** Uses label and translated span as stable semantic identity. */
            override fun semanticIdentity(finding: GlobalFinding<SyntheticFinding>): String =
                "${finding.value.label}:${finding.startUtf8}:${finding.endUtf8}"

            /** Requires all synthetic metadata to agree for one identity. */
            override fun hasEquivalentMetadata(
                first: SyntheticFinding,
                second: SyntheticFinding,
            ): Boolean = first == second

            /** Orders by detector-owned rank before global position. */
            override val canonicalComparator: Comparator<GlobalFinding<SyntheticFinding>> =
                compareBy({ finding -> finding.value.rank }, GlobalFinding<SyntheticFinding>::startUtf8)
        }

    private companion object {
        /** Returns exact UTF-8 byte length for independent expected detector offsets. */
        fun utf8Size(value: String): Long = value.toByteArray(StandardCharsets.UTF_8).size.toLong()
    }
}
