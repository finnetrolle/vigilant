package io.vigilant.detectors.pii.benchmark.hivetrace

import io.vigilant.detectors.pii.PiiType
import io.vigilant.detectors.pii.quality.PiiQualitySpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Independent aggregate fixtures reach each pinned qualification rejection boundary. */
class HiveTraceQualificationTest {
    /** Validates the complete two-split shape before independently corrupting counts, types and domain distribution. */
    @Test
    fun `qualification rejects aggregate type and domain count mismatches`() {
        val corpora = qualifiedExample()
        HiveTraceQualification.validate(corpora)
        val entity = corpora[0]
        val domain = corpora[1]
        val extra = domain.copy(totalCases = 901, cases = domain.cases + domain.cases.last())
        assertEquals("PINNED_COVERAGE", failure(listOf(entity, extra)))
        val changedTypes = domain.cases.toMutableList()
        val first = changedTypes.first()
        changedTypes[0] = HiveTraceCase(first.domain, first.text,
            first.sourceTypes.map { if (it == "EMAIL") "PHONE_NUMBER" else it }, first.sourceGold, first.productGold)
        assertEquals("PINNED_COVERAGE", failure(listOf(entity, domain.copy(cases = changedTypes))))
        val changedDomains = domain.cases.toMutableList()
        changedDomains[0] = HiveTraceCase("S-BANK", first.text, first.sourceTypes, first.sourceGold, first.productGold)
        assertEquals("DOMAIN_COVERAGE", failure(listOf(entity, domain.copy(cases = changedDomains))))
        assertEquals("SPLITS", failure(listOf(domain)))
        assertEquals("SPLITS", failure(corpora.reversed()))
    }

    /** Calls the actual qualifier and returns only its safe category for a deliberately corrupted state. */
    private fun failure(corpora: List<HiveTraceCorpus>): String =
        assertFailsWith<HiveTraceFailure> { HiveTraceQualification.validate(corpora) }.code

    /** Builds source-agreed literal counts without reading metadata or using the qualifier as an oracle. */
    private fun qualifiedExample(): List<HiveTraceCorpus> {
        val types = linkedMapOf(
            "EMAIL" to PiiType.EMAIL_ADDRESS, "PHONE_NUMBER" to PiiType.PHONE_NUMBER,
            "BANK_CARD_NUMBER" to PiiType.PAYMENT_CARD, "INN" to PiiType.RU_INN,
            "SNILS" to PiiType.RU_SNILS, "PASSPORT_NUMBER" to PiiType.RU_PASSPORT,
            "NAME" to null, "ADDRESS" to null, "CVC" to null, "KPP" to null,
            "OGRN" to null, "OGRNIP" to null, "TOKEN" to null,
        )
        val entityRows = types.flatMap { (label, type) ->
            List(70) {
                val gold = type?.let { listOf(PiiQualitySpan(it, 0, 1)) }.orEmpty()
                HiveTraceCase(label, "", listOf(label), gold, gold)
            }
        }
        val counts = listOf(103, 147, 22, 48, 27, 50, 158, 106, 7, 24, 23, 17, 25)
        val labels = types.keys.zip(counts).flatMap { (label, count) -> List(count) { label } }
        val domains = listOf("L-CHAT", "L-DIALOG", "S-AUTO", "S-BANK", "S-DELIVERY",
            "S-HR", "S-RE", "S-SUPPORT", "S-TELECOM")
        var excluded = 0
        val domainRows = List(900) { index ->
            val sourceTypes = if (index < 522) {
                labels.filterIndexed { ordinal, _ -> ordinal % 522 == index }
            } else {
                emptyList()
            }
            val source = sourceTypes.mapNotNull { label -> types[label]?.let { PiiQualitySpan(it, 0, 1) } }
            val product = sourceTypes.mapNotNull { label ->
                if (label == "INN" && excluded < 28) {
                    excluded++
                    null
                } else {
                    types[label]?.let { PiiQualitySpan(it, 0, 1) }
                }
            }
            HiveTraceCase(domains[index % 9], "", sourceTypes, source, product)
        }
        return listOf(HiveTraceCorpus("entity", 910, entityRows, emptyMap()),
            HiveTraceCorpus("domain", 900, domainRows, emptyMap()))
    }
}
