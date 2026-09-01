package io.vigilant.windowing

import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiDetector
import io.vigilant.detectors.pii.PiiFinding
import io.vigilant.detectors.pii.PiiType
import io.vigilant.detectors.pii.fast.FastPiiDetector
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Timeout

/** Public fragment-seam tests for bounded windowed Fast PII execution. */
@Suppress("MaxLineLength")
class WindowedFastPiiExecutorTest {
    /** A finding crossing the detector limit is returned once in original UTF-8 coordinates. */
    @Test
    fun `finding across a window boundary has exact original offsets`() {
        val prefix = " ".repeat(FastPiiWindowCapability.VERSIONED.maxWindowUtf8Bytes - 5)
        val candidate = "alice@example.com"
        val fragment = InspectableTextFragment(prefix + candidate, FragmentReference("message-7"))
        val executorService = Executors.newFixedThreadPool(1)

        try {
            val result =
                WindowedFastPiiExecutor(executorService)
                    .inspect(fragment, setOf(PiiType.EMAIL_ADDRESS))
                    .get(10, TimeUnit.SECONDS)
            val success = assertIs<WindowedPiiInspectionResult.Success>(result)
            val finding = success.findings.single()

            assertEquals(fragment.provenance, success.provenance)
            assertEquals(PiiType.EMAIL_ADDRESS, finding.type)
            assertEquals(prefix.toByteArray(StandardCharsets.UTF_8).size.toLong(), finding.startUtf8)
            assertEquals((prefix + candidate).toByteArray(StandardCharsets.UTF_8).size.toLong(), finding.endUtf8)
        } finally {
            executorService.shutdownNow()
        }
    }

    /** Every documented built-in PII surface remains detectable when its evidence crosses a window boundary. */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Suppress("LongMethod")
    fun `versioned fast pii boundary corpus covers every supported type`() {
        val cases =
            listOf(
                BoundaryCase(PiiType.EMAIL_ADDRESS, "alice.smith+alerts@example.com"),
                BoundaryCase(PiiType.EMAIL_ADDRESS, "user@пример.рф"),
                BoundaryCase(PiiType.PHONE_NUMBER, "+79123456789"),
                BoundaryCase(PiiType.PHONE_NUMBER, "89123456789"),
                BoundaryCase(PiiType.PHONE_NUMBER, "8 912 345-67-89"),
                BoundaryCase(PiiType.PHONE_NUMBER, "8 (912) 345-67-89"),
                BoundaryCase(PiiType.PHONE_NUMBER, "+7 (912) 345-67-89"),
                BoundaryCase(PiiType.PAYMENT_CARD, "4222222222222"),
                BoundaryCase(PiiType.PAYMENT_CARD, "4111 1111 1111 1111"),
                BoundaryCase(PiiType.PAYMENT_CARD, "4111-1111-1111-1111"),
                BoundaryCase(PiiType.PAYMENT_CARD, "4000000000000000006"),
                BoundaryCase(PiiType.IP_ADDRESS, "192.168.1.1"),
                BoundaryCase(PiiType.IP_ADDRESS, "2001:0db8:0000:0000:0000:ff00:0042:8329"),
                BoundaryCase(PiiType.IP_ADDRESS, "2001:db8::ff00:42:8329"),
                BoundaryCase(PiiType.IP_ADDRESS, "::1"),
                BoundaryCase(PiiType.IP_ADDRESS, "::"),
                BoundaryCase(PiiType.IP_ADDRESS, "::ffff:192.0.2.128"),
                BoundaryCase(PiiType.IP_ADDRESS, "2001:db8:0:0:0:ffff:192.0.2.128"),
                BoundaryCase(PiiType.IBAN, "DE89370400440532013000"),
                BoundaryCase(PiiType.IBAN, "de89 3704 0044 0532 0130 00"),
                BoundaryCase(PiiType.RU_INN, "500100732259"),
                BoundaryCase(PiiType.RU_SNILS, "11223344595"),
                BoundaryCase(PiiType.RU_SNILS, "112-233-445-95"),
                BoundaryCase(PiiType.RU_SNILS, "112-233-445 95"),
                BoundaryCase(PiiType.RU_PASSPORT, "Паспорт 4503 123456"),
                BoundaryCase(PiiType.RU_PASSPORT, "Паспорт 45 03 123456"),
                BoundaryCase(PiiType.RU_PASSPORT, "Паспорт 45-03 123456"),
                BoundaryCase(PiiType.RU_PASSPORT, "Паспорт 45 03 № 123456"),
                BoundaryCase(PiiType.RU_OMS, "1234567890123452"),
                BoundaryCase(PiiType.RU_OMS, "1234 5678 9012 3452"),
            )
        val executorService = Executors.newFixedThreadPool(1)

        try {
            val executor = WindowedFastPiiExecutor(executorService)
            val directDetector = FastPiiDetector()
            val capability = FastPiiWindowCapability.VERSIONED
            val requiredContext = capability.maximumEvidenceSpanUtf8Bytes!! - 1
            val firstCoreEnd = capability.maxWindowUtf8Bytes - 2 * requiredContext
            val tail = " ".repeat(2 * requiredContext + 16)
            cases.forEachIndexed { caseIndex, case ->
                val directFindings = directDetector.detect(case.surface, false, setOf(case.type))
                assertEquals(1, directFindings.size, "${case.type.name} surface $caseIndex")
                val directFinding = directFindings.single()
                val findingMidpoint = ((directFinding.startUtf8 + directFinding.endUtf8) / 2).toInt()
                val prefix = " ".repeat(firstCoreEnd - findingMidpoint)
                val fragment = InspectableTextFragment(prefix + case.surface + tail, FragmentReference(case.type.name))
                val success =
                    assertIs<WindowedPiiInspectionResult.Success>(
                        executor.inspect(fragment, setOf(case.type)).get(10, TimeUnit.SECONDS),
                    )
                assertEquals(1, success.findings.size, "${case.type.name} windowed surface $caseIndex")
                val finding = success.findings.single()
                val prefixBytes = prefix.toByteArray(StandardCharsets.UTF_8).size.toLong()
                val expectedFinding =
                    directFinding.copy(
                        startUtf8 = prefixBytes + directFinding.startUtf8,
                        endUtf8 = prefixBytes + directFinding.endUtf8,
                    )

                assertEquals(expectedFinding, finding, case.type.name)
            }
        } finally {
            executorService.shutdownNow()
        }
    }

    /** ASCII and every multi-byte UTF-8 width preserve an email crossing a window boundary. */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `all unicode boundary widths preserve exact original offsets`() {
        val candidate = "alice@example.com"
        val capability = FastPiiWindowCapability.VERSIONED
        val requiredContext = capability.maximumEvidenceSpanUtf8Bytes!! - 1
        val firstCoreEnd = capability.maxWindowUtf8Bytes - 2 * requiredContext
        val targetPrefixBytes =
            firstCoreEnd -
                candidate.toByteArray(StandardCharsets.UTF_8).size / 2
        val tail = " ".repeat(2 * requiredContext + 16)
        val executorService = Executors.newFixedThreadPool(1)

        try {
            val executor = WindowedFastPiiExecutor(executorService)
            listOf(" ", "\u00a0", "€", "😃").forEach { filler ->
                val width = filler.toByteArray(StandardCharsets.UTF_8).size
                val prefix = filler.repeat(targetPrefixBytes / width) + " ".repeat(targetPrefixBytes % width)
                val fragment = InspectableTextFragment(prefix + candidate + tail, FragmentReference("utf8-$width"))
                val success =
                    assertIs<WindowedPiiInspectionResult.Success>(
                        executor.inspect(fragment, setOf(PiiType.EMAIL_ADDRESS)).get(10, TimeUnit.SECONDS),
                    )
                assertEquals(1, success.findings.size, "UTF-8 width $width")
                val finding = success.findings.single()

                assertEquals(targetPrefixBytes.toLong(), finding.startUtf8, "UTF-8 width $width")
                assertEquals(
                    (targetPrefixBytes + candidate.toByteArray(StandardCharsets.UTF_8).size).toLong(),
                    finding.endUtf8,
                    "UTF-8 width $width",
                )
            }
        } finally {
            executorService.shutdownNow()
        }
    }

    /** A finding fully contained in the overlap is returned once across Unicode boundaries. */
    @Test
    fun `overlap duplicates are removed deterministically across unicode boundaries`() {
        val capability = FastPiiWindowCapability.VERSIONED
        val requiredContext = capability.maximumEvidenceSpanUtf8Bytes!! - 1
        val firstCoreEnd = capability.maxWindowUtf8Bytes - 2 * requiredContext
        val candidate = "alice@example.com"
        val candidateBytes = candidate.toByteArray(StandardCharsets.UTF_8).size
        val prefix = " ".repeat(firstCoreEnd - candidateBytes / 2) + "😃"
        val fragment =
            InspectableTextFragment(
                prefix + candidate + " ".repeat(2 * requiredContext + 16),
                FragmentReference("unicode"),
            )
        val executorService = Executors.newFixedThreadPool(1)
        val windowsWithFinding = AtomicInteger()
        val productionDetector = FastPiiDetector()
        val recordingDetector =
            object : PiiDetector {
                /** Counts detector windows that independently observe the overlap candidate. */
                override fun detect(
                    payload: String,
                    stopOnFirst: Boolean,
                    enabledTypes: Set<PiiType>,
                ): List<PiiFinding> =
                    productionDetector.detect(payload, stopOnFirst, enabledTypes).also { findings ->
                        if (findings.isNotEmpty()) {
                            windowsWithFinding.incrementAndGet()
                        }
                    }
            }

        try {
            val success =
                assertIs<WindowedPiiInspectionResult.Success>(
                    WindowedFastPiiExecutor(executorService, recordingDetector, capability)
                        .inspect(fragment, setOf(PiiType.EMAIL_ADDRESS))
                        .get(10, TimeUnit.SECONDS),
                )

            assertEquals(1, success.findings.size)
            assertEquals(prefix.toByteArray(StandardCharsets.UTF_8).size.toLong(), success.findings.single().startUtf8)
            assertEquals(2, windowsWithFinding.get())
        } finally {
            executorService.shutdownNow()
        }
    }

    /** Invalid or unbounded capabilities fail before any unsafe detector invocation. */
    @Test
    fun `capability validation returns stable safe outcomes before detection`() {
        val invalidCapabilities =
            listOf(
                WindowedCapability("", 32, 16),
                WindowedCapability("test", 0, 1),
                WindowedCapability("test", -1, 1),
                WindowedCapability("test", 32, -1),
                WindowedCapability("test", 32, 0),
                WindowedCapability("test", 32, 32),
                WindowedCapability("test", 32, 33),
                WindowedCapability("test", 32, 16),
                WindowedCapability("test", 32, 30),
            )
        val invocations = AtomicInteger()
        val detector = detector { invocations.incrementAndGet(); emptyList() }

        invalidCapabilities.forEach { capability ->
            val executorService = Executors.newSingleThreadExecutor()
            try {
                val result =
                    WindowedFastPiiExecutor(executorService, detector, capability)
                        .inspect(InspectableTextFragment("text", FragmentReference("invalid")), emptySet())
                        .get(5, TimeUnit.SECONDS)

                assertEquals(
                    WindowedPiiInspectionResult.Error(WindowedPiiInspectionErrorCode.INVALID_CAPABILITY),
                    result,
                )
            } finally {
                executorService.shutdownNow()
            }
        }

        val executorService = Executors.newSingleThreadExecutor()
        try {
            val directResult =
                WindowedFastPiiExecutor(
                    executorService,
                    detector,
                    WindowedCapability("unbounded", 8, null),
                ).inspect(InspectableTextFragment("short", FragmentReference("unbounded-direct")), emptySet())
                    .get(5, TimeUnit.SECONDS)
            assertIs<WindowedPiiInspectionResult.Success>(directResult)

            val result =
                WindowedFastPiiExecutor(
                    executorService,
                    detector,
                    WindowedCapability("unbounded", 8, null),
                ).inspect(InspectableTextFragment("123456789", FragmentReference("unbounded")), emptySet())
                    .get(5, TimeUnit.SECONDS)

            assertEquals(
                WindowedPiiInspectionResult.Error(WindowedPiiInspectionErrorCode.WINDOWING_UNSUPPORTED),
                result,
            )
            assertEquals(1, invocations.get())
        } finally {
            executorService.shutdownNow()
        }
    }

    /** Invalid spans, detector errors, and conflicting duplicates discard every partial finding. */
    @Test
    @Suppress("LongMethod")
    fun `detector contract violations return one safe error without partial findings`() {
        val cases =
            listOf(
                DetectorFailureCase(
                    detector { listOf(finding(1, 10)) },
                    "short",
                    WindowedPiiInspectionErrorCode.INVALID_DETECTOR_RESULT,
                ),
                DetectorFailureCase(
                    detector { listOf(finding(1, 2)) },
                    "€",
                    WindowedPiiInspectionErrorCode.INVALID_DETECTOR_RESULT,
                ),
                DetectorFailureCase(
                    detector { error("secret detector detail") },
                    "short",
                    WindowedPiiInspectionErrorCode.DETECTOR_ERROR,
                ),
            )

        cases.forEach { case ->
            val executorService = Executors.newSingleThreadExecutor()
            try {
                val result =
                    WindowedFastPiiExecutor(
                        executorService,
                        case.detector,
                        WindowedCapability("test", 32, 8),
                    ).inspect(InspectableTextFragment(case.fragment, FragmentReference("secret locator")), setOf(PiiType.EMAIL_ADDRESS))
                        .get(5, TimeUnit.SECONDS)

                assertEquals(WindowedPiiInspectionResult.Error(case.expectedCode), result)
                assertTrue("secret" !in result.toString())
            } finally {
                executorService.shutdownNow()
            }
        }

        listOf(
            finding(3, 8, version = "two"),
            finding(3, 8, evidenceStrength = EvidenceStrength.VALIDATED),
            finding(3, 8, confidence = 0.5),
        ).forEach { conflictingFinding ->
            val conflictingDetector = detector { listOf(finding(3, 8), conflictingFinding) }
            val executorService = Executors.newSingleThreadExecutor()
            try {
                val result =
                    WindowedFastPiiExecutor(
                        executorService,
                        conflictingDetector,
                        WindowedCapability("test", 32, 8),
                    ).inspect(InspectableTextFragment(" ".repeat(40), FragmentReference("conflict")), setOf(PiiType.EMAIL_ADDRESS))
                        .get(5, TimeUnit.SECONDS)

                assertEquals(
                    WindowedPiiInspectionResult.Error(WindowedPiiInspectionErrorCode.INCONSISTENT_WINDOW_RESULT),
                    result,
                )
            } finally {
                executorService.shutdownNow()
            }
        }
    }

    /** Invalid Unicode fails before detection and a later window error stops every remaining call. */
    @Test
    fun `invalid fragment and detector error stop execution without partial findings`() {
        val invalidFragmentInvocations = AtomicInteger()
        val invalidFragmentDetector = detector { invalidFragmentInvocations.incrementAndGet(); emptyList() }
        val invalidExecutor = Executors.newSingleThreadExecutor()
        try {
            val result =
                WindowedFastPiiExecutor(invalidExecutor, invalidFragmentDetector, WindowedCapability("test", 32, 8))
                    .inspect(InspectableTextFragment("\ud800", FragmentReference("invalid-unicode")), emptySet())
                    .get(5, TimeUnit.SECONDS)

            assertEquals(
                WindowedPiiInspectionResult.Error(WindowedPiiInspectionErrorCode.INVALID_FRAGMENT),
                result,
            )
            assertEquals(0, invalidFragmentInvocations.get())
        } finally {
            invalidExecutor.shutdownNow()
        }

        val windowInvocations = AtomicInteger()
        val failingDetector =
            detector {
                if (windowInvocations.incrementAndGet() == 2) {
                    error("secret detector detail")
                }
                listOf(finding(1, 3))
            }
        val failingExecutor = Executors.newSingleThreadExecutor()
        try {
            val result =
                WindowedFastPiiExecutor(failingExecutor, failingDetector, WindowedCapability("test", 32, 8))
                    .inspect(InspectableTextFragment(" ".repeat(80), FragmentReference("no-partial")), setOf(PiiType.EMAIL_ADDRESS))
                    .get(5, TimeUnit.SECONDS)

            assertEquals(
                WindowedPiiInspectionResult.Error(WindowedPiiInspectionErrorCode.DETECTOR_ERROR),
                result,
            )
            assertEquals(2, windowInvocations.get())
            assertTrue("secret" !in result.toString())
        } finally {
            failingExecutor.shutdownNow()
        }
    }

    /** Window calls stay exhaustive, bounded, sequential, and confined to the configured CPU executor. */
    @Test
    fun `window execution obeys exhaustive bounded sequential resource contract`() {
        val activeCalls = AtomicInteger()
        val maximumActiveCalls = AtomicInteger()
        val stopOnFirstValues = ArrayList<Boolean>()
        val windowSizes = ArrayList<Int>()
        val detectorThreads = ArrayList<String>()
        val productionDetector = FastPiiDetector()
        val recordingDetector =
            object : PiiDetector {
                /** Records public invocation constraints and delegates to production recognition. */
                override fun detect(
                    payload: String,
                    stopOnFirst: Boolean,
                    enabledTypes: Set<PiiType>,
                ): List<PiiFinding> {
                    val active = activeCalls.incrementAndGet()
                    maximumActiveCalls.accumulateAndGet(active, ::maxOf)
                    return try {
                        stopOnFirstValues += stopOnFirst
                        windowSizes += payload.toByteArray(StandardCharsets.UTF_8).size
                        detectorThreads += Thread.currentThread().name
                        productionDetector.detect(payload, stopOnFirst, enabledTypes)
                    } finally {
                        activeCalls.decrementAndGet()
                    }
                }
            }
        val executorService = Executors.newSingleThreadExecutor { task -> Thread(task, "bounded-pii-cpu") }

        try {
            val success =
                assertIs<WindowedPiiInspectionResult.Success>(
                    WindowedFastPiiExecutor(
                        executorService,
                        recordingDetector,
                        WindowedCapability("resource-proof", 32, 8),
                    ).inspect(InspectableTextFragment(" ".repeat(80), FragmentReference("resources")), setOf(PiiType.EMAIL_ADDRESS))
                        .get(5, TimeUnit.SECONDS),
                )

            assertEquals(emptyList(), success.findings)
            assertTrue(windowSizes.size > 1)
            assertTrue(stopOnFirstValues.all { stopOnFirst -> !stopOnFirst })
            assertTrue(windowSizes.all { size -> size <= 32 })
            assertEquals(1, maximumActiveCalls.get())
            assertEquals(setOf("bounded-pii-cpu"), detectorThreads.toSet())
        } finally {
            executorService.shutdownNow()
        }
    }

    /** Fast PII receives the immutable enabled-type snapshot captured before CPU execution. */
    @Test
    fun `enabled PII types are snapshotted before executor handoff`() {
        val blockerStarted = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val observedTypes = AtomicReference<Set<PiiType>>()
        val detector =
            object : PiiDetector {
                /** Records the detector input snapshot presented after the queued handoff. */
                override fun detect(
                    payload: String,
                    stopOnFirst: Boolean,
                    enabledTypes: Set<PiiType>,
                ): List<PiiFinding> {
                    observedTypes.set(enabledTypes)
                    return emptyList()
                }
            }
        val executorService = Executors.newSingleThreadExecutor()

        try {
            executorService.submit {
                blockerStarted.countDown()
                releaseBlocker.await(5, TimeUnit.SECONDS)
            }
            assertTrue(blockerStarted.await(5, TimeUnit.SECONDS))
            val enabledTypes = mutableSetOf(PiiType.EMAIL_ADDRESS)
            val future =
                WindowedFastPiiExecutor(executorService, detector, WindowedCapability("snapshot", 32, 8))
                    .inspect(InspectableTextFragment("text", FragmentReference("snapshot")), enabledTypes)

            enabledTypes.clear()
            enabledTypes += PiiType.PHONE_NUMBER
            releaseBlocker.countDown()
            assertIs<WindowedPiiInspectionResult.Success>(future.get(5, TimeUnit.SECONDS))

            assertEquals(setOf(PiiType.EMAIL_ADDRESS), observedTypes.get())
        } finally {
            releaseBlocker.countDown()
            executorService.shutdownNow()
        }
    }

    /** Success findings are canonical, immutable, and produced only on the configured CPU executor. */
    @Test
    fun `successful execution is canonical immutable and off the caller thread`() {
        val detectorThread = AtomicReference<String>()
        val expected =
            listOf(
                finding(1, 3, version = "v2", recognizerId = "a"),
                finding(1, 3, version = "v1", recognizerId = "b"),
                finding(1, 3, version = "v1", recognizerId = "z"),
                finding(1, 3, version = "v1", recognizerId = "z", type = PiiType.PHONE_NUMBER),
                finding(1, 4, version = "v1", recognizerId = "z", type = PiiType.PHONE_NUMBER),
                finding(5, 7, version = "v2", recognizerId = "z"),
            )
        val detector =
            detector {
                detectorThread.set(Thread.currentThread().name)
                expected.reversed() + expected.first()
            }
        val executorService = Executors.newSingleThreadExecutor { task -> Thread(task, "pii-cpu-test") }

        try {
            val success =
                assertIs<WindowedPiiInspectionResult.Success>(
                    WindowedFastPiiExecutor(executorService, detector, WindowedCapability("test", 32, 8))
                        .inspect(InspectableTextFragment("12345678", FragmentReference("ordered")), setOf(PiiType.EMAIL_ADDRESS))
                        .get(5, TimeUnit.SECONDS),
                )

            assertEquals(expected, success.findings)
            assertEquals("pii-cpu-test", detectorThread.get())
            assertFailsWith<UnsupportedOperationException> {
                @Suppress("UNCHECKED_CAST")
                (success.findings as MutableList<PiiFinding>).clear()
            }
        } finally {
            executorService.shutdownNow()
        }
    }

    /** Cancelling the returned future interrupts active detection and publishes no result. */
    @Test
    fun `future cancellation stops active detector execution`() {
        val started = CountDownLatch(1)
        val detector =
            detector {
                started.countDown()
                while (!Thread.currentThread().isInterrupted) {
                    Thread.onSpinWait()
                }
                throw CancellationException()
            }
        val executorService = Executors.newSingleThreadExecutor()

        try {
            val future =
                WindowedFastPiiExecutor(executorService, detector, WindowedCapability("test", 32, 8))
                    .inspect(InspectableTextFragment("text", FragmentReference("cancelled")), setOf(PiiType.EMAIL_ADDRESS))
            assertTrue(started.await(5, TimeUnit.SECONDS))

            assertTrue(future.cancel(true))
            assertFailsWith<CancellationException> { future.get() }
        } finally {
            executorService.shutdownNow()
        }
    }

    /** Creates one valid synthetic detector finding. */
    @Suppress("LongParameterList")
    private fun finding(
        start: Long,
        end: Long,
        version: String = "test",
        recognizerId: String = "test.email",
        evidenceStrength: EvidenceStrength = EvidenceStrength.FORMAT_ONLY,
        confidence: Double? = null,
        type: PiiType = PiiType.EMAIL_ADDRESS,
    ): PiiFinding =
        PiiFinding(
            type = type,
            startUtf8 = start,
            endUtf8 = end,
            confidence = confidence,
            evidenceStrength = evidenceStrength,
            recognizerId = recognizerId,
            recognizerVersion = version,
        )

    /** Creates a synthetic detector at the public detector boundary. */
    private fun detector(block: () -> List<PiiFinding>): PiiDetector =
        object : PiiDetector {
            /** Delegates one invocation to the supplied contract behavior. */
            override fun detect(
                payload: String,
                stopOnFirst: Boolean,
                enabledTypes: Set<PiiType>,
            ): List<PiiFinding> = block()
        }

    /** Worked boundary case and candidate-relative prefix. */
    private data class BoundaryCase(
        val type: PiiType,
        val surface: String,
    )

    /** Synthetic public-detector failure case with its fragment and expected safe outcome. */
    private data class DetectorFailureCase(
        val detector: PiiDetector,
        val fragment: String,
        val expectedCode: WindowedPiiInspectionErrorCode,
    )
}
