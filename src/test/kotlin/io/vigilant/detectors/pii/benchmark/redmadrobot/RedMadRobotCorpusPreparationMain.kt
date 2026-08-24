package io.vigilant.detectors.pii.benchmark.redmadrobot

import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Explicit entry point for verified download or offline import of the pinned corpus. */
object RedMadRobotCorpusPreparationMain {
    /** Prepares the pinned corpus without exposing corpus contents in output. */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Expected prepared dataset path and optional offline input path" }
        val destination = Path.of(args[0])
        val offlineInput = args[1].takeIf(String::isNotBlank)?.let(Path::of)
        when (RedMadRobotCorpusPreparer().prepare(destination, offlineInput)) {
            RedMadRobotCorpusSource.OFFLINE ->
                println("Prepared pinned RedMadRobot PII corpus from verified offline input.")
            RedMadRobotCorpusSource.REUSED ->
                println("Reused verified pinned RedMadRobot PII corpus.")
            RedMadRobotCorpusSource.DOWNLOADED ->
                println("Downloaded and verified pinned RedMadRobot PII corpus.")
        }
    }
}

/** Origin of the verified corpus published for a benchmark run. */
internal enum class RedMadRobotCorpusSource {
    OFFLINE,
    REUSED,
    DOWNLOADED,
}

/** Prepares exactly the pinned corpus revision before any benchmark parsing occurs. */
internal class RedMadRobotCorpusPreparer {
    /** Verifies and publishes offline input, reuses a valid output, or downloads the pin. */
    fun prepare(
        destination: Path,
        offlineInput: Path?,
    ): RedMadRobotCorpusSource {
        Files.createDirectories(destination.parent)
        return if (offlineInput != null) {
            prepareOfflineInput(offlineInput, destination)
            RedMadRobotCorpusSource.OFFLINE
        } else if (reuseVerifiedDestination(destination)) {
            RedMadRobotCorpusSource.REUSED
        } else {
            publishAtomically(destination) { temporary ->
                downloadPinnedCorpus(temporary)
                verifyPinnedCorpus(temporary)
            }
            RedMadRobotCorpusSource.DOWNLOADED
        }
    }

    /** Verifies offline bytes and publishes them when they are not already the destination. */
    private fun prepareOfflineInput(
        offlineInput: Path,
        destination: Path,
    ) {
        verifyPinnedCorpus(offlineInput)
        if (!Files.exists(destination) || !Files.isSameFile(offlineInput, destination)) {
            publishAtomically(destination) { temporary ->
                Files.copy(offlineInput, temporary, StandardCopyOption.REPLACE_EXISTING)
                verifyPinnedCorpus(temporary)
            }
        }
    }

    /** Reuses a verified destination and removes only an invalid generated output. */
    private fun reuseVerifiedDestination(destination: Path): Boolean =
        if (!Files.exists(destination)) {
            false
        } else {
            try {
                verifyPinnedCorpus(destination)
                true
            } catch (_: RedMadRobotCorpusIntegrityException) {
                Files.deleteIfExists(destination)
                false
            }
        }

    /** Verifies the exact pinned dataset size and checksum before publication. */
    private fun verifyPinnedCorpus(path: Path) {
        val actualSize = Files.size(path)
        if (actualSize != RedMadRobotBenchmarkMetadata.SIZE_BYTES) {
            throw RedMadRobotCorpusIntegrityException(
                "RedMadRobot dataset size mismatch: " +
                    "expected=${RedMadRobotBenchmarkMetadata.SIZE_BYTES} actual=$actualSize",
            )
        }
        val actualSha256 = sha256(path)
        if (actualSha256 != RedMadRobotBenchmarkMetadata.SHA256) {
            throw RedMadRobotCorpusIntegrityException(
                "RedMadRobot dataset SHA-256 mismatch: " +
                    "expected=${RedMadRobotBenchmarkMetadata.SHA256} actual=$actualSha256",
            )
        }
    }

    /** Calculates SHA-256 without loading the complete corpus into memory. */
    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    /** Downloads the pinned file without reading or printing its response body. */
    private fun downloadPinnedCorpus(destination: Path) {
        val connection =
            URI(RedMadRobotBenchmarkMetadata.DATASET_URL).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        try {
            val status = connection.responseCode
            check(status in 200..299) { "RedMadRobot dataset download failed with HTTP $status" }
            connection.inputStream.use { input ->
                Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            connection.disconnect()
        }
    }
}

/** Integrity failure for bytes that do not match the pinned dataset. */
private class RedMadRobotCorpusIntegrityException(
    message: String,
) : IllegalStateException(message)
