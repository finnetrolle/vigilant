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

/** Focused orchestration tests for the fast PII recognizer pipeline. */
class FastPiiDetectorTest {
    /** Verifies that an empty type selection bypasses payload preflight. */
    @Test
    fun `empty enabled types return no findings without preflight`() {
        val invalidUnicode = charArrayOf('\uD800').concatToString()

        val findings =
            FastPiiDetector().detect(
                payload = invalidUnicode,
                enabledTypes = emptySet(),
            )

        assertEquals(emptyList(), findings)
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
}
