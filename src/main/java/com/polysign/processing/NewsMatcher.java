package com.polysign.processing;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Scores the relevance of a news article to a prediction market question using
 * asymmetric containment / market-side coverage.
 *
 * <p>Algorithm: count how many market keywords appear in the article keyword set,
 * then divide by the total number of market keywords:
 * <pre>
 *   score = |{k ∈ marketKw : k ∈ articleKw}| / |marketKw|
 * </pre>
 * Returns 0.0 when either set is empty (no match possible).
 *
 * <p>This is intentionally <b>not</b> a Jaccard coefficient. Jaccard divides by
 * the union, which penalises long articles with many keywords. Here, a long
 * article that covers all market keywords must score 1.0 — the article
 * “contains” the market topic regardless of how many additional keywords it
 * carries.
 *
 * <p>Used by {@link NewsCorrelationDetector} with a default threshold of 0.5
 * (configurable via {@code polysign.detectors.news.min-score}).
 */
@Component
public class NewsMatcher {

    /**
     * Computes the asymmetric containment / market-side coverage score for a
     * single article–market pair.
     *
     * @param articleKw keyword set extracted from the article (title + description)
     * @param marketKw  keyword set attached to the market question
     * @return score in [0.0, 1.0]; 0.0 if either set is null or empty
     */
    public double score(Set<String> articleKw, Set<String> marketKw) {
        if (articleKw == null || articleKw.isEmpty()) return 0.0;
        if (marketKw  == null || marketKw.isEmpty())  return 0.0;
        long matches = marketKw.stream()
                .filter(articleKw::contains)
                .count();
        return (double) matches / marketKw.size();
    }
}
