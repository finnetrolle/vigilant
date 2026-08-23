package io.vigilant.detectors.pii.fast

import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Provenance and completeness tests for the pinned SWIFT IBAN country-length resource. */
class IbanCountryLengthsTest {
    /** Verifies release 102 provenance, resource parity, representative lengths, and immutability. */
    @Test
    fun `swift release 102 resource matches runtime country lengths`() {
        val stream = requireNotNull(javaClass.getResourceAsStream(REGISTRY_RESOURCE_PATH))
        val resourceLines = stream.bufferedReader(StandardCharsets.UTF_8).use { reader -> reader.readLines() }
        assertEquals(
            listOf(
                "# swift-iban-country-lengths-v1",
                "# source=https://www.swift.com/swift-resource/9606/download?language=en",
                "# release=102",
                "# issued=2026-06",
                "country_code,iban_length",
            ),
            resourceLines.take(PROVENANCE_LINE_COUNT),
        )
        val resourceLengths =
            resourceLines.drop(PROVENANCE_LINE_COUNT).associate { line ->
                val (countryCode, length) = line.split(',')
                countryCode to length.toInt()
            }
        val lengths = IbanCountryLengths.all()

        assertEquals(89, lengths.size)
        assertEquals(resourceLengths, lengths)
        assertEquals(lengths.keys.sorted(), lengths.keys.toList())
        assertEquals(
            mapOf(
                "AD" to 24,
                "BI" to 27,
                "HN" to 28,
                "RU" to 33,
                "YE" to 30,
            ),
            lengths.filterKeys { countryCode -> countryCode in setOf("AD", "BI", "HN", "RU", "YE") },
        )
        assertFailsWith<UnsupportedOperationException> {
            (lengths as MutableMap).clear()
        }
    }

    /** Verifies that the public detector can validate IBAN without reading a runtime resource. */
    @Test
    fun `iban detection does not depend on classpath resource io`() {
        val productionClasses = FastPiiDetector::class.java.protectionDomain.codeSource.location
        val parentLoader = javaClass.classLoader
        val detectorPackagePrefix = "io.vigilant.detectors.pii."
        val registryResource = "io/vigilant/detectors/pii/fast/iban-country-lengths.csv"
        val loader =
            object : URLClassLoader(arrayOf(productionClasses), parentLoader) {
                /** Loads detector package classes from the isolated production output. */
                override fun loadClass(
                    name: String,
                    resolve: Boolean,
                ): Class<*> {
                    if (!name.startsWith(detectorPackagePrefix)) {
                        return super.loadClass(name, resolve)
                    }
                    synchronized(getClassLoadingLock(name)) {
                        val loadedClass = findLoadedClass(name) ?: findClass(name)
                        if (resolve) {
                            resolveClass(loadedClass)
                        }
                        return loadedClass
                    }
                }

                /** Hides the pinned CSV so detector behavior cannot rely on runtime resource I/O. */
                override fun getResource(name: String) =
                    if (name == registryResource) {
                        null
                    } else {
                        super.getResource(name)
                    }
            }

        loader.use {
            val detectorClass = loader.loadClass("${detectorPackagePrefix}fast.FastPiiDetector")
            val detector = detectorClass.getConstructor().newInstance()
            val piiTypeClass = loader.loadClass("${detectorPackagePrefix}PiiType")
            val ibanType = requireNotNull(piiTypeClass.enumConstants).single { constant ->
                (constant as Enum<*>).name == "IBAN"
            }
            val detect =
                detectorClass.getMethod(
                    "detect",
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                    Set::class.java,
                )

            val findings = detect.invoke(detector, "GB82WEST12345698765432", true, setOf(ibanType)) as List<*>

            assertEquals(1, findings.size)
        }
    }

    /** Test constants for the pinned resource schema. */
    private companion object {
        /** Absolute classpath location of the pinned SWIFT registry resource. */
        const val REGISTRY_RESOURCE_PATH = "/io/vigilant/detectors/pii/fast/iban-country-lengths.csv"

        /** Number of provenance and schema lines before registry rows. */
        const val PROVENANCE_LINE_COUNT = 5
    }
}
