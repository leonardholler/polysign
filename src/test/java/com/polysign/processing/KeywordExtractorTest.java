package com.polysign.processing;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link KeywordExtractor}.
 */
class KeywordExtractorTest {

    private final KeywordExtractor extractor = new KeywordExtractor();

    // ── Null / blank inputs ───────────────────────────────────────────────────

    @Test
    void returnsEmptySetForNullInput() {
        assertThat(extractor.extract(null)).isEmpty();
    }

    @Test
    void returnsEmptySetForBlankInput() {
        assertThat(extractor.extract("   ")).isEmpty();
    }

    // ── Stop-word filtering ───────────────────────────────────────────────────

    @Test
    void filtersOutStopWords() {
        Set<String> result = extractor.extract("will the president win the election");
        assertThat(result).doesNotContain("will", "the", "win"); // "win" is 3 chars → below min length
        assertThat(result).contains("president", "election");
    }

    // ── Minimum token length ──────────────────────────────────────────────────

    @Test
    void dropsTokensShorterThan4Chars() {
        Set<String> result = extractor.extract("US UK EU win NATO summit");
        // US, UK, EU are 2 chars; "win" is 3 chars → all dropped (min length is 4)
        assertThat(result).doesNotContain("us", "uk", "eu", "win");
        assertThat(result).contains("nato", "summit");
    }

    // ── Case normalization ────────────────────────────────────────────────────

    @Test
    void lowercasesAllTokens() {
        Set<String> result = extractor.extract("TRUMP ELECTION 2026");
        assertThat(result).contains("trump", "election", "2026");
        assertThat(result).doesNotContain("TRUMP", "ELECTION");
    }

    // ── Non-alphabetic splitting ──────────────────────────────────────────────

    @Test
    void splitsOnNonAlphabeticCharacters() {
        Set<String> result = extractor.extract("election: Democrat vs. Republican? outcome");
        assertThat(result).contains("election", "democrat", "republican", "outcome");
    }

    // ── Deduplication ─────────────────────────────────────────────────────────

    @Test
    void deduplicatesTokens() {
        Set<String> result = extractor.extract("bitcoin price bitcoin price prediction");
        assertThat(result).containsOnlyOnce("bitcoin");
        assertThat(result).containsOnlyOnce("price");
    }

    // ── Realistic market question ─────────────────────────────────────────────

    @Test
    void extractsFromRealisticMarketQuestion() {
        String question = "Will Donald Trump win the 2026 US Senate election in Florida?";
        Set<String> result = extractor.extract(question);
        assertThat(result).contains("donald", "trump", "senate", "election", "florida");
        assertThat(result).doesNotContain("will", "the", "in");
    }
}
