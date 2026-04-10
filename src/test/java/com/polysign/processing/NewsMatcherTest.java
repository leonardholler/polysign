package com.polysign.processing;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Unit tests for {@link NewsMatcher} asymmetric containment scoring.
 */
class NewsMatcherTest {

    private final NewsMatcher matcher = new NewsMatcher();

    @Test
    void returnsZeroWhenMarketKeywordsEmpty() {
        double score = matcher.score(Set.of("election", "result"), Collections.emptySet());
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    void returnsZeroWhenArticleKeywordsEmpty() {
        double score = matcher.score(Collections.emptySet(), Set.of("election", "result"));
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    void returnsOneOnFullOverlap() {
        Set<String> kw = Set.of("trump", "election", "vote");
        double score = matcher.score(kw, kw);
        assertThat(score).isEqualTo(1.0);
    }

    @Test
    void returnsHalfOnHalfOverlap() {
        Set<String> article = Set.of("trump", "vote");
        Set<String> market  = Set.of("trump", "vote", "election", "result");
        double score = matcher.score(article, market);
        assertThat(score).isCloseTo(0.5, offset(0.001));
    }

    @Test
    void returnsZeroOnNoOverlap() {
        double score = matcher.score(
                Set.of("cryptocurrency", "bitcoin"),
                Set.of("election", "vote", "president"));
        assertThat(score).isEqualTo(0.0);
    }

    /**
     * Asymmetry test: article has 200 keywords including all 4 market keywords.
     * Score must be 1.0 regardless of article keyword count.
     */
    @Test
    void returnsOneWhenAllMarketKeywordsPresentDespiteExtraArticleKeywords() {
        // 196 filler keywords + the 4 market keywords
        Set<String> filler = IntStream.range(0, 196)
                .mapToObj(i -> "word" + i)
                .collect(Collectors.toSet());
        Set<String> market  = Set.of("election", "vote", "trump", "result");
        Set<String> article = new java.util.HashSet<>(filler);
        article.addAll(market);

        double score = matcher.score(article, market);
        assertThat(score).isEqualTo(1.0);
    }
}
