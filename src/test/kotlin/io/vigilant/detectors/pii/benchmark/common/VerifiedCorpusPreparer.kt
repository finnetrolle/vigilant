package io.vigilant.detectors.pii.benchmark.common

import io.vigilant.detectors.pii.benchmark.redmadrobot.publishAtomically
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Public identity of one pinned external artifact, never an individual source record. */
internal data class CorpusFilePin(val file: String, val url: String, val sizeBytes: Long, val sha256: String)

/** Safe shared preparation category, without paths, response bodies or original causes. */
internal class CorpusPreparationFailure(val code: String) : IllegalStateException("Corpus preparation: $code")

/** Verifies each input on every invocation, including previously published cache files. */
internal class VerifiedCorpusPreparer(
    private val pins: List<CorpusFilePin>,
    private val connectionFactory: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
) {
    /** Imports pinned offline files or downloads missing/invalid cached files; returns only a complete corpus. */
    fun prepare(destination: Path, offline: Path? = null) = preparationSafely {
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

    /** Verifies every pinned file before the benchmark opens any Parquet reader. */
    fun verifyDirectory(directory: Path) = preparationSafely {
        pins.forEach { verify(directory.resolve(it.file), it) }
    }

    /** Rejects missing, truncated, oversized and wrong-content files using the same offline/online contract. */
    fun verify(path: Path, pin: CorpusFilePin) = preparationSafely {
        if (!Files.isRegularFile(path) || Files.size(path) != pin.sizeBytes) throw CorpusPreparationFailure("SIZE")
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
        if (actual != pin.sha256) throw CorpusPreparationFailure("SHA256")
    }

    /** Treats an invalid cache as a miss while retaining explicit offline failures. */
    private fun isVerified(path: Path, pin: CorpusFilePin): Boolean = try {
        verify(path, pin)
        true
    } catch (_: CorpusPreparationFailure) {
        false
    }

    /** Owns and closes the HTTP connection and streams, with a pinned upper bound on downloaded bytes. */
    private fun download(target: Path, pin: CorpusFilePin) {
        val connection = connectionFactory(URI(pin.url))
        AutoCloseable { connection.disconnect() }.use {
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            if (connection.responseCode !in 200..299) throw CorpusPreparationFailure("HTTP_STATUS")
            connection.inputStream.use { input ->
                Files.newOutputStream(target).use { output ->
                    copyBounded(input, output, pin.sizeBytes)
                }
            }
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
            if (total > limit) throw CorpusPreparationFailure("SIZE")
            output.write(buffer, 0, count)
        }
    }
}

/** Erases external I/O and cleanup diagnostics while preserving a finite integrity category. */
@Suppress("SwallowedException") // Source-bearing causes and suppressed close failures cannot cross this boundary.
private fun <T> preparationSafely(action: () -> T): T = try {
    action()
} catch (failure: CorpusPreparationFailure) {
    throw CorpusPreparationFailure(failure.code)
} catch (_: Exception) {
    throw CorpusPreparationFailure("INPUT_IO")
}
