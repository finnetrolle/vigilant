package io.vigilant.gateway

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpStatus
import java.io.IOException
import java.net.ServerSocket
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Shared lifecycle fixture for E2E tests that launch the production gateway
 * entry point in a bounded child JVM.
 */
internal class GatewayProcessFixture private constructor(
    val process: Process,
    val port: Int,
    private val outputBuffer: StringBuilder,
    private val outputReader: Thread,
) : AutoCloseable {
    /**
     * Waits until [probePath] answers successfully, failing fast when the
     * child exits and including its safely captured output in the failure.
     */
    fun awaitServing(probePath: String = "/readyz"): WebClient {
        val client = WebClient.builder("http://127.0.0.1:$port")
            .responseTimeout(CLIENT_RESPONSE_TIMEOUT)
            .build()
        val deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos()
        var lastFailure: Throwable? = null
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) {
                throw AssertionError("gateway exited before readiness; output: ${output()}")
            }
            try {
                if (client.get(probePath).aggregate().join().status() == HttpStatus.OK) return client
            } catch (failure: Throwable) {
                lastFailure = failure
            }
            Thread.sleep(READINESS_POLL_MILLIS)
        }
        throw AssertionError("gateway did not become ready; output: ${output()}", lastFailure)
    }

    /** Returns a thread-safe snapshot of merged child stdout and stderr. */
    fun output(): String = synchronized(outputBuffer) { outputBuffer.toString() }

    /** Force-stops the child if necessary and joins its output reader. */
    override fun close() {
        if (process.isAlive) process.destroyForcibly()
        process.waitFor(PROCESS_EXIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
        outputReader.join(OUTPUT_READER_JOIN_MILLIS)
    }

    internal companion object {
        /**
         * Launches the production entry point with [upstream], optional JVM
         * arguments, and scenario-specific environment overrides.
         */
        fun launch(
            upstream: URI,
            jvmArguments: List<String> = emptyList(),
            environment: Map<String, String> = emptyMap(),
        ): GatewayProcessFixture {
            val port = reserveGatewayPort()
            val command = buildList {
                add("${System.getProperty("java.home")}/bin/java")
                addAll(jvmArguments)
                add("-cp")
                add(System.getProperty("java.class.path"))
                add("io.vigilant.gateway.MainKt")
            }
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .withTestPolicyConfiguration()
                .apply {
                    environment().apply {
                        put("VIGILANT_UPSTREAM_URL", upstream.toString())
                        put("VIGILANT_PORT", port.toString())
                        putAll(environment)
                    }
                }.start()
            val output = StringBuilder()
            val outputReader = thread(name = "gateway-$port-output") {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(output) { output.append(line).append('\n') }
                }
            }
            return GatewayProcessFixture(process, port, output, outputReader)
        }

        /**
         * Reserves a free non-ephemeral port that fixture servers cannot take
         * from the OS ephemeral allocation range before the child binds it.
         */
        private fun reserveGatewayPort(): Int {
            while (true) {
                val candidate = Random.nextInt(MIN_GATEWAY_PORT, MAX_GATEWAY_PORT)
                try {
                    ServerSocket(candidate).use { return candidate }
                } catch (_: IOException) {
                    // An unrelated local process owns this port; try another.
                }
            }
        }

        private const val MIN_GATEWAY_PORT = 1024
        private const val MAX_GATEWAY_PORT = 49152
        private const val READINESS_POLL_MILLIS = 100L
        private const val OUTPUT_READER_JOIN_MILLIS = 5_000L
        private val STARTUP_TIMEOUT: Duration = Duration.ofSeconds(30)
        private val CLIENT_RESPONSE_TIMEOUT: Duration = Duration.ofSeconds(5)
        private val PROCESS_EXIT_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
