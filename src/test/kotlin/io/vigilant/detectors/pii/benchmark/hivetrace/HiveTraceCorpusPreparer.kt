package io.vigilant.detectors.pii.benchmark.hivetrace

import io.vigilant.detectors.pii.benchmark.redmadrobot.publishAtomically
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Verifies each input on every invocation, including previously published cache files. */
internal class HiveTraceCorpusPreparer(private val pins: List<HiveTracePin> = HiveTraceMetadata.pins) {
    /** Imports both offline files or downloads missing/invalid cached files; returns only a complete corpus. */
    fun prepare(destination: Path, offline: Path? = null) = safely {
        Files.createDirectories(destination)
        pins.forEach { pin ->
            val target = destination.resolve(pin.file)
            if (offline != null) {
                val source = offline.resolve(pin.file)
                verify(source, pin)
                if (!Files.exists(target) || !Files.isSameFile(source, target)) {
                    publishAtomically(target) { temporary ->
                        Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
                        verify(temporary, pin)
                    }
                }
            } else if (!isVerified(target, pin)) {
                Files.deleteIfExists(target)
                publishAtomically(target) { temporary ->
                    download(temporary, pin)
                    verify(temporary, pin)
                }
            }
        }
        verifyDirectory(destination)
    }

    /** Verifies every split before the benchmark opens any Parquet reader. */
    fun verifyDirectory(directory: Path) = safely {
        pins.forEach { verify(directory.resolve(it.file), it) }
    }

    /** Rejects missing, truncated, oversized and wrong-content files using the same offline/online contract. */
    fun verify(path: Path, pin: HiveTracePin) = safely {
        if (!Files.isRegularFile(path) || Files.size(path) != pin.sizeBytes) throw HiveTraceFailure("SIZE")
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != pin.sha256) throw HiveTraceFailure("SHA256")
    }

    /** Treats an invalid cache as a miss while retaining explicit offline failures. */
    private fun isVerified(path: Path, pin: HiveTracePin): Boolean = try {
        verify(path, pin)
        true
    } catch (_: HiveTraceFailure) {
        false
    }

    /** Owns and closes the HTTP connection and streams, with a pinned upper bound on downloaded bytes. */
    private fun download(target: Path, pin: HiveTracePin) {
        val connection = URI(pin.url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        try {
            if (connection.responseCode !in 200..299) throw HiveTraceFailure("HTTP_STATUS")
            connection.inputStream.use { input ->
                Files.newOutputStream(target).use { output ->
                    copyBounded(input, output, pin.sizeBytes)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Stops oversized responses before writing any bytes beyond the pinned size. */
    private fun copyBounded(input: InputStream, output: OutputStream, limit: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw HiveTraceFailure("SIZE")
            output.write(buffer, 0, count)
        }
    }

}

/** Discards unsafe I/O/parser messages, causes and suppressed cleanup failures at the external-data boundary. */
@Suppress("SwallowedException") // Original cleanup diagnostics can contain external source values.
internal fun <T> safely(action: () -> T): T = try {
    action()
} catch (failure: HiveTraceFailure) {
    throw HiveTraceFailure(failure.code)
} catch (_: Exception) {
    throw HiveTraceFailure("INPUT_IO")
}

/** Explicit non-gating preparation entry point, never attached to ordinary build/test. */
object HiveTraceCorpusPreparationMain {
    /** Prepares both pinned split files without printing paths or source fields. */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Expected corpus directory and optional offline directory" }
        HiveTraceCorpusPreparer().prepare(Path.of(args[0]), args[1].takeIf(String::isNotBlank)?.let(Path::of))
        println("Prepared verified HiveTrace corpus.")
    }
}
