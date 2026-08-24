package io.vigilant.detectors.pii.quality

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** End-to-end test for the canonical report entry point over repository resources. */
class CanonicalQualityReportMainTest {
    /** Verifies that the report command gates all corpora and publishes reviewed counts. */
    @Test
    fun `report command scores repository corpora and writes both artifacts`(@TempDir output: Path) {
        CanonicalQualityReportMain.main(arrayOf(output.toString()))

        val json = ObjectMapper().readTree(Files.readString(output.resolve("pii-quality-report.json")))
        assertEquals(900, json.at("/corpus/positiveCases").intValue())
        assertEquals(900, json.at("/corpus/hardNegativeCases").intValue())
        assertEquals(3, json.at("/corpus/mixedCases").intValue())
        assertEquals(13, json.at("/metrics/aggregate/exact/truePositives").intValue())
        assertEquals(13, json.at("/metrics/aggregate/relaxed/truePositives").intValue())
        assertEquals(true, Files.exists(output.resolve("pii-quality-report.md")))
    }
}
