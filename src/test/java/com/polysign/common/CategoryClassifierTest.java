package com.polysign.common;

import org.junit.jupiter.api.Test;

import static com.polysign.common.CategoryClassifier.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CategoryClassifier}.
 *
 * Covers the five primary categories plus the four new sub-categories
 * (ai_tech, weather_science, elections_intl, current_events) added in phase-20.
 */
class CategoryClassifierTest {

    // ── Primary categories ────────────────────────────────────────────────────

    @Test
    void crypto_matchesBitcoin() {
        assertThat(classify("Will Bitcoin reach $100k?", null)).isEqualTo(CRYPTO);
    }

    @Test
    void politics_matchesElection() {
        assertThat(classify("Who will win the 2026 US Senate election?", null)).isEqualTo(POLITICS);
    }

    @Test
    void sports_matchesNba() {
        assertThat(classify("Will the NBA championship go to game 7?", null)).isEqualTo(SPORTS);
    }

    @Test
    void economics_matchesFedRate() {
        assertThat(classify("Will the Federal Reserve cut rates in June?", null)).isEqualTo(ECONOMICS);
    }

    @Test
    void entertainment_matchesOscar() {
        assertThat(classify("Will Dune win the Best Picture Oscar?", null)).isEqualTo(ENTERTAINMENT);
    }

    // ── New sub-categories ────────────────────────────────────────────────────

    @Test
    void aiTech_matchesOpenAi() {
        assertThat(classify("Will OpenAI release GPT-5 by end of 2025?", null)).isEqualTo(AI_TECH);
    }

    @Test
    void aiTech_matchesChatGpt() {
        assertThat(classify("How many users will ChatGPT have by Q3?", null)).isEqualTo(AI_TECH);
    }

    @Test
    void aiTech_matchesNvidia() {
        assertThat(classify("Will Nvidia's market cap exceed $4T this year?", null)).isEqualTo(AI_TECH);
    }

    @Test
    void aiTech_matchesArtificialIntelligence() {
        assertThat(classify("Will artificial intelligence replace software engineers by 2030?", null))
                .isEqualTo(AI_TECH);
    }

    @Test
    void weatherScience_matchesHurricane() {
        assertThat(classify("Will a Category 5 hurricane hit the US mainland in 2025?", null))
                .isEqualTo(WEATHER_SCIENCE);
    }

    @Test
    void weatherScience_matchesNasa() {
        assertThat(classify("Will NASA land astronauts on the Moon before 2027?", null))
                .isEqualTo(WEATHER_SCIENCE);
    }

    @Test
    void weatherScience_matchesClimateChange() {
        assertThat(classify("Will 2025 set a global warming temperature record?", null))
                .isEqualTo(WEATHER_SCIENCE);
    }

    @Test
    void electionsIntl_matchesModi() {
        assertThat(classify("Will Modi win a third term as India's prime minister?", null))
                .isEqualTo(ELECTIONS_INTL);
    }

    @Test
    void electionsIntl_matchesTrudeau() {
        assertThat(classify("Will Trudeau resign as Canada's PM before the election?", null))
                .isEqualTo(ELECTIONS_INTL);
    }

    @Test
    void electionsIntl_matchesEuParliament() {
        assertThat(classify("Which party will win the most seats in EU parliament?", null))
                .isEqualTo(ELECTIONS_INTL);
    }

    @Test
    void currentEvents_matchesLawsuit() {
        assertThat(classify("Will the DOJ lawsuit against Google result in a breakup?", null))
                .isEqualTo(CURRENT_EVENTS);
    }

    @Test
    void currentEvents_matchesMerger() {
        assertThat(classify("Will the proposed airline merger receive regulatory approval?", null))
                .isEqualTo(CURRENT_EVENTS);
    }

    @Test
    void currentEvents_matchesBankruptcy() {
        assertThat(classify("Will the retail chain file for bankruptcy this year?", null))
                .isEqualTo(CURRENT_EVENTS);
    }

    // ── Fallback ──────────────────────────────────────────────────────────────

    @Test
    void other_whenNoKeywordMatches() {
        assertThat(classify("Will the number of penguins in Antarctica increase?", null))
                .isEqualTo(OTHER);
    }

    // ── Ordering: crypto wins over politics for "bitcoin price" ───────────────

    @Test
    void crypto_beatsPolitic_forBitcoinPrice() {
        assertThat(classify("Will bitcoin price reach $200k under Trump?", null)).isEqualTo(CRYPTO);
    }

    // ── Event slug used when question alone would miss ─────────────────────────

    @Test
    void eventSlug_triggersCategory() {
        // slug contains leader name → elections_intl (question is null / irrelevant)
        assertThat(classify(null, "will-modi-visit-washington")).isEqualTo(ELECTIONS_INTL);
    }
}
