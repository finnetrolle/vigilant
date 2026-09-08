package io.vigilant.gateway

import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpStatus
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Shared lifecycle fixture for E2E tests that launch the production gateway
 * entry point in a bounded child JVM.
 */
internal class GatewayProcessFixture internal constructor(
    /** Fixture-owned child process. */
    val process: Process,
    /** Loopback port reserved for this child and never returned again by this worker. */
    val port: Int,
    /** Complete standard-output lines drained by the fixture-owned stdout reader. */
    private val stdoutBuffer: StringBuilder,
    /** Complete standard-error lines drained by the fixture-owned stderr reader. */
    private val stderrBuffer: StringBuilder,
    /** Signals each appended child-output line to deterministic observers. */
    private val outputSignal: Semaphore,
    /** Reader threads, deferred failures, and the shared fixture terminal marker. */
    private val outputLifecycle: GatewayProcessOutputLifecycle,
) : AutoCloseable {
    /** Isolates readiness and scenario requests from process-global connection reuse. */
    private val clientFactory = ClientFactory.builder().build()

    /** Adapts one prebuilt reader for low-level fixture lifecycle tests. */
    internal constructor(
        process: Process,
        port: Int,
        outputBuffer: StringBuilder,
        outputSignal: Semaphore,
        outputReader: GatewayProcessOutputReader,
        shutdownStarted: AtomicBoolean = AtomicBoolean(),
    ) : this(
        process,
        port,
        outputBuffer,
        StringBuilder(),
        outputSignal,
        GatewayProcessOutputLifecycle(
            listOf(outputReader),
            shutdownStarted,
        ),
    )

    /**
     * Waits until [probePath] answers successfully, failing fast when the
     * child exits and including its safely captured output in the failure.
     */
    fun awaitServing(probePath: String = "/readyz"): WebClient {
        val client = WebClient.builder("http://127.0.0.1:$port")
            .factory(clientFactory)
            .responseTimeout(CLIENT_RESPONSE_TIMEOUT)
            .build()
        val deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos()
        var lastFailure: Throwable? = null
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) {
                failStartup("gateway exited before readiness", lastFailure)
            }
            try {
                if (client.get(probePath).aggregate().join().status() == HttpStatus.OK) return client
            } catch (failure: Throwable) {
                lastFailure = failure
            }
            Thread.sleep(READINESS_POLL_MILLIS)
        }
        failStartup("gateway did not become ready", lastFailure)
    }

    /** Returns a thread-safe snapshot of child stdout. */
    fun stdout(): String = synchronized(stdoutBuffer) { stdoutBuffer.toString() }

    /** Returns a thread-safe snapshot of child stderr. */
    fun stderr(): String = synchronized(stderrBuffer) { stderrBuffer.toString() }

    /** Returns a safe combined snapshot of child stdout and stderr. */
    fun output(): String = stdout() + stderr()

    /**
     * Waits until [predicate] accepts a child-output snapshot or [timeout] expires.
     * Each wait is driven by an appended output line and failure reports the last snapshot.
     */
    fun awaitOutput(
        timeout: Duration,
        predicate: (String) -> Boolean,
    ): String {
        val deadline = System.nanoTime() + timeout.toNanos()
        var snapshot = output()
        while (!predicate(snapshot)) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L || !outputSignal.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
                throw AssertionError("gateway output condition was not observed: $snapshot")
            }
            snapshot = output()
        }
        return snapshot
    }

    /** Waits for normal child exit and complete output-reader publication within [timeout]. */
    fun awaitExit(timeout: Duration = PROCESS_EXIT_TIMEOUT): GatewayProcessExit {
        if (!process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
            failStartup("gateway child process did not exit within $timeout", null)
        }
        awaitReaders()
        return GatewayProcessExit(process.exitValue(), stdout(), stderr())
    }

    /** Returns whether either fixture-owned output reader remains alive. */
    internal fun hasLiveOutputReaders(): Boolean =
        outputLifecycle.readers.any { reader -> reader.thread.isAlive }

    /** Stops the child, closes every process stream, and proves both output readers terminated. */
    override fun close() {
        if (!outputLifecycle.shutdownStarted.compareAndSet(false, true)) return
        closeAllResources(
            { if (process.isAlive) process.destroy() },
            {
                if (!process.waitFor(PROCESS_EXIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                    if (process.isAlive) process.destroyForcibly()
                    check(process.waitFor(PROCESS_EXIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                        "gateway child process did not stop after forced termination"
                    }
                }
                check(!process.isAlive) { "gateway child process remained alive after termination" }
            },
            process.outputStream::close,
            ::awaitReaders,
            clientFactory::closeWithinTestTimeout,
            process.inputStream::close,
            process.errorStream::close,
        )
    }

    /** Closes the fixture on startup failure and attaches any cleanup failure to the public assertion. */
    private fun failStartup(message: String, cause: Throwable?): Nothing {
        val observedState = processState()
        val cleanupFailure = runCatching(::close).exceptionOrNull()
        val failure =
            AssertionError(
                "$message; observedProcess=$observedState; finalProcess=${processState()}; " +
                    "lastOutput=${output()}",
                cause,
            )
        cleanupFailure?.let(failure::addSuppressed)
        throw failure
    }

    /** Reports the current child exit state without reading any request or response data. */
    private fun processState(): String =
        if (process.isAlive) "alive=true" else "alive=false,exitCode=${process.exitValue()}"

    /** Boundedly joins every reader and retains later reader failures as suppressed evidence. */
    private fun awaitReaders() {
        closeAllResources(
            *outputLifecycle.readers.map { reader -> { joinReader(reader) } }.toTypedArray(),
        )
    }

    /** Interrupts and rejoins one reader only when natural process termination did not stop it. */
    private fun joinReader(reader: GatewayProcessOutputReader) {
        reader.thread.join(OUTPUT_READER_JOIN_MILLIS)
        if (reader.thread.isAlive) {
            reader.thread.interrupt()
            reader.thread.join(OUTPUT_READER_JOIN_MILLIS)
        }
        check(!reader.thread.isAlive) { "gateway output reader did not stop: ${reader.thread.name}" }
        reader.failure.get()?.let { failure ->
            throw IllegalStateException("gateway output reader failed: ${reader.thread.name}", failure)
        }
    }

    internal companion object {
        /**
         * Launches the production entry point with [upstream], optional JVM and
         * application arguments, and scenario-specific environment overrides.
         */
        fun launch(
            upstream: URI,
            jvmArguments: List<String> = emptyList(),
            environment: Map<String, String> = emptyMap(),
            arguments: List<String> = emptyList(),
        ): GatewayProcessFixture {
            val port = reserveNonEphemeralPort()
            val command = mainKtCommand(jvmArguments, arguments)
            return launchCommand(command, upstream, environment, emptySet(), port)
        }

        /**
         * Launches the installed application script produced by `installDist`
         * with scenario-specific [environment] and application [arguments],
         * proving the packaged runtime graph rather than the test classpath.
         */
        fun launchInstalled(
            upstream: URI,
            environment: Map<String, String>,
            arguments: List<String> = emptyList(),
        ): GatewayProcessFixture {
            val port = reserveNonEphemeralPort()
            val executable =
                java.nio.file.Path.of("build", "install", "vigilant", "bin", "vigilant")
                    .toAbsolutePath()
                    .normalize()
            require(java.nio.file.Files.isExecutable(executable)) {
                "installed vigilant launcher is missing; run installDist"
            }
            return launchCommand(listOf(executable.toString()) + arguments, upstream, environment, emptySet(), port)
        }

        /**
         * Launches `MainKt` for an expected bounded startup rejection with
         * environment removals, JVM/application arguments, and separate stderr.
         */
        fun launchForStartupRejection(
            environment: Map<String, String>,
            removedEnvironment: Set<String> = emptySet(),
            jvmArguments: List<String> = emptyList(),
            arguments: List<String> = emptyList(),
        ): GatewayProcessFixture {
            val port = reserveNonEphemeralPort()
            val command = mainKtCommand(jvmArguments, arguments)
            return launchCommand(command, null, environment, removedEnvironment, port)
        }

        /** Builds the canonical test-classpath command for one `MainKt` child process. */
        private fun mainKtCommand(jvmArguments: List<String>, arguments: List<String>): List<String> =
            buildList {
                add("${System.getProperty("java.home")}/bin/java")
                addAll(jvmArguments)
                add("-cp")
                add(System.getProperty("java.class.path"))
                add("io.vigilant.gateway.MainKt")
                addAll(arguments)
            }

        /** Starts one selected gateway command with the shared bounded output lifecycle. */
        private fun launchCommand(
            command: List<String>,
            upstream: URI?,
            environment: Map<String, String>,
            removedEnvironment: Set<String>,
            port: Int,
        ): GatewayProcessFixture {
            val process = ProcessBuilder(command)
                .withTestRuntimeConfiguration(environment)
                .apply {
                    environment().apply {
                        upstream?.let { put("VIGILANT_UPSTREAM_URL", it.toString()) }
                        putIfAbsent("VIGILANT_UPSTREAM_URL", "http://127.0.0.1:18081")
                        put("VIGILANT_PORT", port.toString())
                        removedEnvironment.forEach(::remove)
                    }
                }.start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val outputSignal = Semaphore(0)
            val outputLifecycle =
                GatewayProcessOutputLifecycle.start(process, stdout, stderr, outputSignal, port)
            return GatewayProcessFixture(
                process,
                port,
                stdout,
                stderr,
                outputSignal,
                outputLifecycle,
            )
        }

        /**
         * Reserves and releases the next free loopback port from the task-local monotonic registry.
         * A returned port is never considered again during this test-worker lifetime.
         *
         * @return a currently unused local TCP port suitable for test fixtures.
         */
        @Synchronized
        fun reserveNonEphemeralPort(): Int {
            while (nextGatewayPort.get() < MAX_GATEWAY_PORT) {
                val candidate = nextGatewayPort.getAndIncrement()
                try {
                    ServerSocket().use { socket ->
                        socket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), candidate))
                        return candidate
                    }
                } catch (_: IOException) {
                    // An unrelated local process owns this port; try another.
                }
            }
            error("task-local gateway port registry exhausted")
        }

        /** First candidate below the OS ephemeral allocation range. */
        private const val MIN_GATEWAY_PORT = 10_240

        /** Exclusive upper bound of the non-ephemeral candidate range. */
        private const val MAX_GATEWAY_PORT = 49152

        /** Next never-before-returned candidate in this test worker's monotonic registry. */
        private val nextGatewayPort = AtomicInteger(MIN_GATEWAY_PORT)

        /** Interval between readiness probes when no child terminal event is available. */
        private const val READINESS_POLL_MILLIS = 100L

        /** Maximum natural and post-interrupt join time for each output reader. */
        private const val OUTPUT_READER_JOIN_MILLIS = 5_000L

        /** Bounded startup deadline for a newly launched gateway. */
        private val STARTUP_TIMEOUT: Duration = Duration.ofSeconds(30)

        /** Per-probe client deadline that leaves time for another readiness observation. */
        private val CLIENT_RESPONSE_TIMEOUT: Duration = Duration.ofSeconds(5)

        /** Bounded graceful and forced child-process exit deadline. */
        private val PROCESS_EXIT_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}

/** Shared ownership state for every output reader attached to one child process. */
internal data class GatewayProcessOutputLifecycle(
    /** Readers started at launch and boundedly joined after every child terminal path. */
    val readers: List<GatewayProcessOutputReader>,
    /** Marker set before fixture shutdown causes expected process-stream termination. */
    val shutdownStarted: AtomicBoolean,
) {
    internal companion object {
        /** Starts both process stream readers before returning their shared lifecycle owner. */
        fun start(
            process: Process,
            stdout: StringBuilder,
            stderr: StringBuilder,
            outputSignal: Semaphore,
            port: Int,
        ): GatewayProcessOutputLifecycle {
            val shutdownStarted = AtomicBoolean()

            return GatewayProcessOutputLifecycle(
                readers =
                    listOf(
                        GatewayProcessOutputReader.start(
                            process.inputStream,
                            stdout,
                            outputSignal,
                            "gateway-$port-stdout",
                            shutdownStarted,
                        ),
                        GatewayProcessOutputReader.start(
                            process.errorStream,
                            stderr,
                            outputSignal,
                            "gateway-$port-stderr",
                            shutdownStarted,
                        ),
                    ),
                shutdownStarted = shutdownStarted,
            )
        }
    }
}

/** One fixture-owned output reader together with its deferred failure observation. */
internal class GatewayProcessOutputReader private constructor(
    /** Reader thread whose lifecycle is bounded by the fixture. */
    val thread: Thread,
    /** First uncaught reader failure, published for the fixture's terminal join. */
    val failure: AtomicReference<Throwable?>,
) {
    internal companion object {
        /** Starts one named stream reader whose every unexpected failure is observable at fixture termination. */
        fun start(
            stream: java.io.InputStream,
            buffer: StringBuilder,
            outputSignal: Semaphore,
            readerName: String,
            shutdownStarted: AtomicBoolean,
        ): GatewayProcessOutputReader {
            val readerThread =
                thread(start = false, name = readerName) {
                    try {
                        stream.bufferedReader().forEachLine { line ->
                            synchronized(buffer) { buffer.append(line).append('\n') }
                            outputSignal.release()
                        }
                    } catch (failure: IOException) {
                        if (!shutdownStarted.get() || failure.message !in EXPECTED_STREAM_CLOSED_MESSAGES) {
                            throw failure
                        }
                    }
                }
            return start(readerThread)
        }

        /** Requires a new [thread], installs failure capture, starts it, and returns its owned observation. */
        fun start(thread: Thread): GatewayProcessOutputReader {
            require(thread.state == Thread.State.NEW) { "gateway output reader must not start before failure capture" }
            val failure = AtomicReference<Throwable?>()
            thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, cause ->
                failure.compareAndSet(null, cause)
            }
            return GatewayProcessOutputReader(thread, failure).also { thread.start() }
        }

        /** Exact JDK/macOS process-pipe failures emitted when shutdown closes a blocking reader. */
        private val EXPECTED_STREAM_CLOSED_MESSAGES = setOf("Stream closed", "Bad file descriptor")

    }
}

/** Complete, separately captured observation of one expected gateway process exit. */
internal data class GatewayProcessExit(
    /** Child exit status. */
    val exitCode: Int,
    /** Complete child standard output after both readers terminate. */
    val stdout: String,
    /** Complete child standard error after both readers terminate. */
    val stderr: String,
)
