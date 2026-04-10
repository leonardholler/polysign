package com.polysign.processing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link UrlCanonicalizer}.
 *
 * Verifies: UTM stripping, fragment stripping, host lowercasing,
 * trailing-slash removal, preservation of legitimate query params,
 * and SHA-256 stability.
 */
class UrlCanonicalizerTest {

    // ── 1. Strip UTM tracking parameters ─────────────────────────────────────

    @Test
    void stripsUtmParams() {
        String raw = "https://example.com/article?utm_source=twitter&utm_medium=social&utm_campaign=news";
        String canonical = UrlCanonicalizer.canonicalize(raw);
        assertThat(canonical).doesNotContain("utm_source", "utm_medium", "utm_campaign");
        assertThat(canonical).isEqualTo("https://example.com/article");
    }

    // ── 2. Strip fragment (#...) ──────────────────────────────────────────────

    @Test
    void stripsFragment() {
        String raw = "https://reuters.com/world/us#main-content";
        String canonical = UrlCanonicalizer.canonicalize(raw);
        assertThat(canonical).doesNotContain("#");
        assertThat(canonical).isEqualTo("https://reuters.com/world/us");
    }

    // ── 3. Lowercase host ─────────────────────────────────────────────────────

    @Test
    void lowercasesHost() {
        String raw = "https://WWW.BBC.CO.UK/news/world/article";
        String canonical = UrlCanonicalizer.canonicalize(raw);
        assertThat(canonical).startsWith("https://www.bbc.co.uk");
    }

    // ── 4. Strip trailing slash from path ────────────────────────────────────

    @Test
    void stripsTrailingSlash() {
        String raw = "https://politico.com/story/2026/04/election/";
        String canonical = UrlCanonicalizer.canonicalize(raw);
        assertThat(canonical).isEqualTo("https://politico.com/story/2026/04/election");
    }

    // ── 5. Preserve legitimate query params ──────────────────────────────────

    @Test
    void preservesLegitimateQueryParams() {
        String raw = "https://api.example.com/search?q=election&page=2";
        String canonical = UrlCanonicalizer.canonicalize(raw);
        assertThat(canonical).contains("q=election");
        assertThat(canonical).contains("page=2");
    }

    // ── 6. Mixed: UTM + fragment + legitimate param ───────────────────────────

    @Test
    void stripsTrackingButPreservesLegitimateParamWithFragment() {
        String raw = "https://bloomberg.com/markets?utm_source=email&id=12345#section";
        String canonical = UrlCanonicalizer.canonicalize(raw);
        assertThat(canonical).doesNotContain("utm_source", "#");
        assertThat(canonical).contains("id=12345");
    }

    // ── 7. All tracking params stripped leaves no query string ────────────────

    @Test
    void allTrackingParamsStrippedProducesCleanUrl() {
        String raw = "https://example.com/path?utm_source=a&utm_medium=b&fbclid=c&gclid=d";
        String canonical = UrlCanonicalizer.canonicalize(raw);
        assertThat(canonical).isEqualTo("https://example.com/path");
    }

    // ── 8. SHA-256 is deterministic ───────────────────────────────────────────

    @Test
    void sha256IsDeterministic() {
        String url = "https://bloomberg.com/article/2026-04-09?utm_source=twitter#top";
        String id1 = UrlCanonicalizer.sha256(url);
        String id2 = UrlCanonicalizer.sha256(url);
        assertThat(id1).isEqualTo(id2);
        assertThat(id1).hasSize(64).matches("[0-9a-f]+");
    }

    // ── 9. Different canonical forms produce different hashes ─────────────────

    @Test
    void differentUrlsProduceDifferentHashes() {
        String id1 = UrlCanonicalizer.sha256("https://example.com/article/one");
        String id2 = UrlCanonicalizer.sha256("https://example.com/article/two");
        assertThat(id1).isNotEqualTo(id2);
    }

    // ── 10. Same URL with/without UTM produces same hash (dedup works) ────────

    @Test
    void urlWithAndWithoutUtmProduceSameHash() {
        String withUtm    = "https://example.com/story?utm_source=fb";
        String withoutUtm = "https://example.com/story";
        assertThat(UrlCanonicalizer.sha256(withUtm))
                .isEqualTo(UrlCanonicalizer.sha256(withoutUtm));
    }
}
