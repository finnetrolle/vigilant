package io.vigilant.detectors.pii.fast

import io.vigilant.detectors.pii.PiiDetectionError
import io.vigilant.detectors.pii.PiiDetectionException
import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiType
import java.util.concurrent.CancellationException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Timeout

/** Focused orchestration tests for the fast PII recognizer pipeline. */
class FastPiiDetectorTest {
    /** Verifies that an empty type selection bypasses Unicode and size preflight. */
    @Test
    fun `empty enabled types return no findings without preflight`() {
        val uninspectedPayloads =
            listOf(
                charArrayOf('\uD800').concatToString(),
                "a".repeat(1_048_577),
            )

        uninspectedPayloads.forEach { payload ->
            val findings = FastPiiDetector().detect(payload = payload, enabledTypes = emptySet())

            assertEquals(emptyList(), findings)
        }
    }

    /** Verifies that selecting a type performs full payload preflight. */
    @Test
    fun `non-empty enabled types validate the payload before recognition`() {
        val invalidUnicode = charArrayOf('\uD800').concatToString()
        val invocations = mutableListOf<PiiType>()
        val detector =
            FastPiiDetector.withRecognizers(
                listOf(FakeRecognizer(PiiType.EMAIL_ADDRESS, invocations)),
            )

        val exception =
            assertFailsWith<PiiDetectionException> {
                detector.detect(
                    payload = invalidUnicode,
                    enabledTypes = setOf(PiiType.EMAIL_ADDRESS),
                )
            }

        assertEquals(PiiDetectionError.INVALID_UNICODE, exception.code)
        assertEquals(emptyList(), invocations)
    }

    /** Verifies that a validated empty payload does not invoke recognizers. */
    @Test
    fun `empty payload returns no findings after preflight`() {
        val invocations = mutableListOf<PiiType>()
        val detector =
            FastPiiDetector.withRecognizers(
                listOf(FakeRecognizer(PiiType.EMAIL_ADDRESS, invocations)),
            )

        val findings =
            detector.detect(
                payload = "",
                enabledTypes = setOf(PiiType.EMAIL_ADDRESS),
            )

        assertEquals(emptyList(), invocations)
        assertEquals(emptyList(), findings)
    }

    /** Verifies canonical recognizer ordering after filtering disabled types. */
    @Test
    fun `enabled recognizers run in canonical type order`() {
        val invocations = mutableListOf<PiiType>()
        val canonicalOrder =
            listOf(
                PiiType.EMAIL_ADDRESS,
                PiiType.PHONE_NUMBER,
                PiiType.PAYMENT_CARD,
                PiiType.IP_ADDRESS,
                PiiType.IBAN,
                PiiType.RU_INN,
                PiiType.RU_SNILS,
                PiiType.RU_PASSPORT,
                PiiType.RU_OMS,
            )
        val enabledTypes = canonicalOrder.toSet() - PiiType.PHONE_NUMBER
        val detector =
            FastPiiDetector.withRecognizers(
                canonicalOrder.reversed().map { type -> FakeRecognizer(type, invocations) },
            )

        val findings =
            detector.detect(
                payload = "abc",
                stopOnFirst = false,
                enabledTypes = enabledTypes,
            )

        val expectedOrder = canonicalOrder.filter { type -> type in enabledTypes }
        assertEquals(expectedOrder, invocations)
        assertEquals(expectedOrder, findings.map { it.type })
    }

    /** Verifies canonical merge ordering, cross-type overlaps, and exact duplicate removal. */
    @Test
    fun `full detection preserves overlaps and removes only exact duplicates`() {
        val detector = mergeSemanticsDetector()

        val findings =
            detector.detect(
                payload = "abcdef",
                stopOnFirst = false,
                enabledTypes = setOf(PiiType.EMAIL_ADDRESS, PiiType.PHONE_NUMBER),
            )

        assertEquals(
            listOf(
                FindingIdentity(PiiType.EMAIL_ADDRESS, 1L, 4L, "fake.email.a"),
                FindingIdentity(PiiType.EMAIL_ADDRESS, 1L, 4L, "fake.email.z"),
                FindingIdentity(PiiType.EMAIL_ADDRESS, 1L, 5L, "fake.email.a"),
                FindingIdentity(PiiType.EMAIL_ADDRESS, 3L, 6L, "fake.email.z"),
                FindingIdentity(PiiType.PHONE_NUMBER, 1L, 4L, "fake.phone"),
            ),
            findings.map { finding ->
                FindingIdentity(
                    type = finding.type,
                    startUtf8 = finding.startUtf8,
                    endUtf8 = finding.endUtf8,
                    recognizerId = finding.recognizerId,
                )
            },
        )
    }

    /** Verifies early exit against the canonical full result for one recognizer type. */
    @Test
    fun `stop on first equals canonical merge result`() {
        val detector = mergeSemanticsDetector()
        val enabledTypes = setOf(PiiType.EMAIL_ADDRESS, PiiType.PHONE_NUMBER)

        val full = detector.detect("abcdef", stopOnFirst = false, enabledTypes = enabledTypes)
        val first = detector.detect("abcdef", stopOnFirst = true, enabledTypes = enabledTypes)

        assertEquals(listOf(full.first()), first)
    }

    /** Verifies canonical cross-recognizer composition through the built-in public detector. */
    @Test
    fun `built in recognizers compose one canonical mixed result`() {
        val payload = canonicalMixedPayload()

        val findings = FastPiiDetector().detect(payload, stopOnFirst = false)

        assertEquals(
            listOf(
                FindingSpan(PiiType.EMAIL_ADDRESS, 5L, 22L),
                FindingSpan(PiiType.PHONE_NUMBER, 30L, 42L),
                FindingSpan(PiiType.PAYMENT_CARD, 49L, 65L),
                FindingSpan(PiiType.IP_ADDRESS, 70L, 81L),
                FindingSpan(PiiType.IBAN, 88L, 110L),
                FindingSpan(PiiType.RU_INN, 116L, 128L),
                FindingSpan(PiiType.RU_SNILS, 136L, 147L),
                FindingSpan(PiiType.RU_PASSPORT, 164L, 175L),
                FindingSpan(PiiType.RU_OMS, 49L, 65L),
            ),
            findings.map { finding ->
                FindingSpan(finding.type, finding.startUtf8, finding.endUtf8)
            },
        )
    }

    /** Verifies stop-on-first equivalence for full and filtered built-in pipelines. */
    @Test
    fun `stop on first equals the first full finding for every filtered type set`() {
        val detector = FastPiiDetector()
        val enabledTypeSets =
            listOf(
                PiiType.entries.toSet(),
                setOf(PiiType.PAYMENT_CARD, PiiType.IP_ADDRESS, PiiType.IBAN),
                setOf(PiiType.RU_PASSPORT, PiiType.RU_OMS),
                setOf(PiiType.RU_OMS),
            )

        enabledTypeSets.forEach { enabledTypes ->
            val full =
                detector.detect(
                    payload = canonicalMixedPayload(),
                    stopOnFirst = false,
                    enabledTypes = enabledTypes,
                )
            val first =
                detector.detect(
                    payload = canonicalMixedPayload(),
                    stopOnFirst = true,
                    enabledTypes = enabledTypes,
                )

            assertEquals(listOf(full.first()), first)
        }
    }

    /** Verifies deterministic results with unrelated calls interleaved on one detector. */
    @Test
    fun `repeated calls retain no request state`() {
        val detector = FastPiiDetector()
        val expected = detector.detect(canonicalMixedPayload(), stopOnFirst = false)

        repeat(32) {
            assertEquals(
                emptyList(),
                detector.detect(
                    payload = "ordinary text",
                    stopOnFirst = false,
                    enabledTypes = setOf(PiiType.EMAIL_ADDRESS),
                ),
            )
            assertEquals(expected, detector.detect(canonicalMixedPayload(), stopOnFirst = false))
        }
    }

    /** Verifies bounded completion for a maximal mixed payload containing only hard negatives. */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `adversarial mixed no match payload completes without backtracking`() {
        val adversarialUnit =
            "a@b;8;0:;DE00 0000000000000001;500100732258;11223344596;" +
                "4503-123456;1234567890123453;"
        val payload =
            buildString(MAX_PAYLOAD_UTF8_SIZE) {
                while (length + adversarialUnit.length <= MAX_PAYLOAD_UTF8_SIZE) {
                    append(adversarialUnit)
                }
                repeat(MAX_PAYLOAD_UTF8_SIZE - length) { append('.') }
            }

        val findings = FastPiiDetector().detect(payload, stopOnFirst = false)

        assertEquals(emptyList(), findings)
    }

    /** Verifies early exit after the first finding in canonical type order. */
    @Test
    fun `stop on first skips every recognizer after the first finding`() {
        val invocations = mutableListOf<PiiType>()
        val detector =
            FastPiiDetector.withRecognizers(
                listOf(
                    FakeRecognizer(PiiType.PHONE_NUMBER, invocations),
                    FakeRecognizer(PiiType.EMAIL_ADDRESS, invocations),
                ),
            )

        val findings =
            detector.detect(
                payload = "abc",
                stopOnFirst = true,
                enabledTypes = setOf(PiiType.PHONE_NUMBER, PiiType.EMAIL_ADDRESS),
            )

        assertEquals(listOf(PiiType.EMAIL_ADDRESS), invocations)
        assertEquals(listOf(PiiType.EMAIL_ADDRESS), findings.map { it.type })
    }

    /** Verifies that stop-on-first continues past recognizers with no valid finding. */
    @Test
    fun `stop on first continues until a recognizer finds PII`() {
        val invocations = mutableListOf<PiiType>()
        val detector =
            FastPiiDetector.withRecognizers(
                listOf(
                    EmptyRecognizer(PiiType.EMAIL_ADDRESS, invocations),
                    FakeRecognizer(PiiType.PHONE_NUMBER, invocations),
                ),
            )

        val findings =
            detector.detect(
                payload = "abc",
                stopOnFirst = true,
                enabledTypes = setOf(PiiType.EMAIL_ADDRESS, PiiType.PHONE_NUMBER),
            )

        assertEquals(listOf(PiiType.EMAIL_ADDRESS, PiiType.PHONE_NUMBER), invocations)
        assertEquals(listOf(PiiType.PHONE_NUMBER), findings.map { it.type })
    }

    /** Verifies that callers cannot mutate a non-empty detector result. */
    @Test
    fun `detector returns immutable findings`() {
        val detector =
            FastPiiDetector.withRecognizers(
                listOf(FakeRecognizer(PiiType.EMAIL_ADDRESS, mutableListOf())),
            )

        val findings =
            detector.detect(
                payload = "abc",
                stopOnFirst = false,
                enabledTypes = setOf(PiiType.EMAIL_ADDRESS),
            )

        assertFailsWith<UnsupportedOperationException> {
            (findings as MutableList).clear()
        }
    }

    /** Verifies entry cancellation without clearing the caller's interrupt flag. */
    @Test
    fun `interrupted entry cancels detection and preserves interrupt status`() {
        val currentThread = Thread.currentThread()
        currentThread.interrupt()

        try {
            assertFailsWith<CancellationException> {
                FastPiiDetector().detect(
                    payload = "not inspected",
                    enabledTypes = emptySet(),
                )
            }
            assertTrue(currentThread.isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    /** Verifies cancellation between recognizers without invoking the next type. */
    @Test
    fun `interrupt between recognizers cancels before the next recognizer`() {
        val invocations = mutableListOf<PiiType>()
        val detector =
            FastPiiDetector.withRecognizers(
                listOf(
                    InterruptingRecognizer(PiiType.EMAIL_ADDRESS, invocations),
                    FakeRecognizer(PiiType.PHONE_NUMBER, invocations),
                ),
            )

        try {
            assertFailsWith<CancellationException> {
                detector.detect(
                    payload = "abc",
                    stopOnFirst = false,
                    enabledTypes = setOf(PiiType.EMAIL_ADDRESS, PiiType.PHONE_NUMBER),
                )
            }
            assertEquals(listOf(PiiType.EMAIL_ADDRESS), invocations)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    /** Verifies cooperative cancellation between sequential candidate validations. */
    @Test
    fun `recognizer cancellation checkpoint observes interruption`() {
        val detector =
            FastPiiDetector.withRecognizers(
                listOf(ValidationCheckpointRecognizer),
            )

        try {
            assertFailsWith<CancellationException> {
                detector.detect(
                    payload = "abc",
                    enabledTypes = setOf(PiiType.EMAIL_ADDRESS),
                )
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    /** Verifies that interruption after recognition cannot return a partial result. */
    @Test
    fun `interrupt before finding validation cancels without partial findings`() {
        val detector =
            FastPiiDetector.withRecognizers(
                listOf(InterruptingFindingRecognizer),
            )

        try {
            assertFailsWith<CancellationException> {
                detector.detect(
                    payload = "abc",
                    stopOnFirst = false,
                    enabledTypes = setOf(PiiType.EMAIL_ADDRESS),
                )
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    /** Verifies that interleaved calls on one detector retain no request state. */
    @Test
    fun `one detector safely handles concurrent calls`() {
        val detector =
            FastPiiDetector.withRecognizers(
                listOf(BarrierRecognizer(CyclicBarrier(2))),
            )
        val executor = Executors.newFixedThreadPool(2)

        try {
            val asciiResult =
                executor.submit<List<Long>> {
                    detector
                        .detect("a", stopOnFirst = false, enabledTypes = setOf(PiiType.EMAIL_ADDRESS))
                        .map { finding -> finding.endUtf8 }
                }
            val unicodeResult =
                executor.submit<List<Long>> {
                    detector
                        .detect("я😀", stopOnFirst = false, enabledTypes = setOf(PiiType.EMAIL_ADDRESS))
                        .map { finding -> finding.endUtf8 }
                }

            assertEquals(listOf(1L), asciiResult.get(5, TimeUnit.SECONDS))
            assertEquals(listOf(6L), unicodeResult.get(5, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    /** Deterministic recognizer that records each pipeline invocation. */
    private class FakeRecognizer(
        override val type: PiiType,
        private val invocations: MutableList<PiiType>,
    ) : PiiRecognizer {
        /** Records the invocation and returns one fixed valid span. */
        override fun recognize(
            payload: String,
            stopOnFirst: Boolean,
            cancellationCheckpoint: () -> Unit,
        ): List<RecognizedPii> {
            invocations += type
            return listOf(
                RecognizedPii(
                    startCharacter = 0,
                    endCharacter = payload.length,
                    evidenceStrength = EvidenceStrength.FORMAT_ONLY,
                    recognizerId = "fake.${type.name.lowercase()}",
                    recognizerVersion = "test",
                ),
            )
        }
    }

    /** Deterministic recognizer that records its invocation but finds no valid PII. */
    private class EmptyRecognizer(
        override val type: PiiType,
        private val invocations: MutableList<PiiType>,
    ) : PiiRecognizer {
        /** Records the invocation and returns no recognition. */
        override fun recognize(
            payload: String,
            stopOnFirst: Boolean,
            cancellationCheckpoint: () -> Unit,
        ): List<RecognizedPii> {
            invocations += type
            return emptyList()
        }
    }

    /** Deterministic recognizer exposing a preordered recognition script. */
    private class ScriptedRecognizer(
        override val type: PiiType,
        private val recognitions: List<RecognizedPii>,
    ) : PiiRecognizer {
        /** Returns the complete script or its first recognition for early-exit calls. */
        override fun recognize(
            payload: String,
            stopOnFirst: Boolean,
            cancellationCheckpoint: () -> Unit,
        ): List<RecognizedPii> = if (stopOnFirst) recognitions.take(1) else recognitions
    }

    /** Deterministic recognizer that interrupts its caller before returning no finding. */
    private class InterruptingRecognizer(
        override val type: PiiType,
        private val invocations: MutableList<PiiType>,
    ) : PiiRecognizer {
        /** Interrupts the current thread so the pipeline must cancel at its next checkpoint. */
        override fun recognize(
            payload: String,
            stopOnFirst: Boolean,
            cancellationCheckpoint: () -> Unit,
        ): List<RecognizedPii> {
            invocations += type
            Thread.currentThread().interrupt()
            return emptyList()
        }
    }

    /** Fake recognizer that interrupts between two deterministic validation checkpoints. */
    private object ValidationCheckpointRecognizer : PiiRecognizer {
        override val type = PiiType.EMAIL_ADDRESS

        /** Requires the second checkpoint to throw after this fake sets the interrupt flag. */
        override fun recognize(
            payload: String,
            stopOnFirst: Boolean,
            cancellationCheckpoint: () -> Unit,
        ): List<RecognizedPii> {
            cancellationCheckpoint()
            Thread.currentThread().interrupt()
            cancellationCheckpoint()
            error("Cancellation checkpoint did not observe interruption")
        }
    }

    /** Fake recognizer that interrupts immediately before exposing one valid recognition. */
    private object InterruptingFindingRecognizer : PiiRecognizer {
        override val type = PiiType.EMAIL_ADDRESS

        /** Sets the interrupt flag before returning a finding to pipeline validation. */
        override fun recognize(
            payload: String,
            stopOnFirst: Boolean,
            cancellationCheckpoint: () -> Unit,
        ): List<RecognizedPii> {
            Thread.currentThread().interrupt()
            return listOf(
                RecognizedPii(
                    startCharacter = 0,
                    endCharacter = 1,
                    evidenceStrength = EvidenceStrength.FORMAT_ONLY,
                    recognizerId = "fake.interrupting",
                    recognizerVersion = "test",
                ),
            )
        }
    }

    /** Fake recognizer that synchronizes concurrent calls before reading its payload. */
    private class BarrierRecognizer(
        private val barrier: CyclicBarrier,
    ) : PiiRecognizer {
        override val type = PiiType.EMAIL_ADDRESS

        /** Returns a span covering only this invocation's payload after both calls meet. */
        override fun recognize(
            payload: String,
            stopOnFirst: Boolean,
            cancellationCheckpoint: () -> Unit,
        ): List<RecognizedPii> {
            barrier.await(5, TimeUnit.SECONDS)
            return listOf(
                RecognizedPii(
                    startCharacter = 0,
                    endCharacter = payload.length,
                    evidenceStrength = EvidenceStrength.FORMAT_ONLY,
                    recognizerId = "fake.barrier",
                    recognizerVersion = "test",
                ),
            )
        }
    }

    /** Creates one deterministic recognition for merge-semantics tests. */
    private fun recognition(
        startCharacter: Int,
        endCharacter: Int,
        recognizerId: String,
    ): RecognizedPii =
        RecognizedPii(
            startCharacter = startCharacter,
            endCharacter = endCharacter,
            evidenceStrength = EvidenceStrength.FORMAT_ONLY,
            recognizerId = recognizerId,
            recognizerVersion = "test",
        )

    /** Returns one worked mixed payload containing all built-in PII types. */
    private fun canonicalMixedPayload(): String =
        "mail alice@example.com; phone +79123456789; dual 1234567890123452; " +
            "ip 192.168.1.1; iban DE89370400440532013000; inn 500100732259; " +
            "snils 11223344595; паспорт 4503 123456"

    /** Creates the controlled multi-recognizer detector used by merge regressions. */
    private fun mergeSemanticsDetector(): FastPiiDetector =
        FastPiiDetector.withRecognizers(
            listOf(
                ScriptedRecognizer(
                    type = PiiType.PHONE_NUMBER,
                    recognitions = listOf(recognition(1, 4, "fake.phone")),
                ),
                ScriptedRecognizer(
                    type = PiiType.EMAIL_ADDRESS,
                    recognitions =
                        listOf(
                            recognition(1, 4, "fake.email.z"),
                            recognition(3, 6, "fake.email.z"),
                        ),
                ),
                ScriptedRecognizer(
                    type = PiiType.EMAIL_ADDRESS,
                    recognitions =
                        listOf(
                            recognition(1, 4, "fake.email.a"),
                            recognition(1, 4, "fake.email.a"),
                            recognition(1, 5, "fake.email.a"),
                        ),
                ),
            ),
        )

    /** Names every field participating in exact-duplicate and ordering assertions. */
    private data class FindingIdentity(
        val type: PiiType,
        val startUtf8: Long,
        val endUtf8: Long,
        val recognizerId: String,
    )

    /** Names the public type and UTF-8 span used by mixed-result assertions. */
    private data class FindingSpan(
        val type: PiiType,
        val startUtf8: Long,
        val endUtf8: Long,
    )

    /** Holds the detector's normative maximum payload size for orchestration regressions. */
    private companion object {
        /** Maximum valid ASCII payload length in bytes and Kotlin characters. */
        const val MAX_PAYLOAD_UTF8_SIZE = 1_048_576
    }
}
