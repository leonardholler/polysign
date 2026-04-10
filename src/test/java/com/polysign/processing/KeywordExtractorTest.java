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
        assertThat(result).doesNotContain("will", "the");
        assertThat(result).contains("president", "win", "election");
    }

    // ── Minimum token length ──────────────────────────────────────────────────

    @Test
    void dropsTokensShorterThan3Chars() {
        Set<String> result = extractor.extract("US UK EU NATO summit");
        // US, UK, EU are 2 chars → dropped; NATO, summit → kept
        assertThat(result).doesNotContain("us", "uk", "eu");
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
