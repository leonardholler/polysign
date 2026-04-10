package com.polysign.processing;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Stopword-filtered keyword extractor for market questions and article text.
 *
 * <p>Extraction algorithm (deliberately simple — Phase 7 spec, Decision F):
 * <ol>
 *   <li>Lowercase the input</li>
 *   <li>Split on any non-alphabetic character sequence</li>
 *   <li>Drop tokens shorter than 3 characters</li>
 *   <li>Drop tokens that appear in the stop-word list</li>
 *   <li>Return the remaining unique tokens as a {@code Set<String>}</li>
 * </ol>
 *
 * <p>No TF-IDF, no embeddings, no stemming. The 0.5 threshold in
 * {@link NewsCorrelationDetector} and the $100k volume gate are the quality
 * controls. Phase 7.5 backtesting will measure and tune via outcomes data.
 *
 * <p>Shared between {@link com.polysign.poller.MarketPoller} (market keywords
 * extracted once at upsert time) and {@link com.polysign.poller.RssPoller}
 * (article keywords extracted from title + description at ingest time).
 */
@Component
public class KeywordExtractor {

    /**
     * Standard English stop words (~150 words).
     * Source: Correction 3 from Phase 7 plan review — verbatim list.
     */
    private static final Set<String> STOP_WORDS = Set.of(
        "a","an","the","and","or","but","not","no","if","then","than",
        "so","as","is","am","are","was","were","be","been","being",
        "have","has","had","do","does","did","doing","will","would",
        "shall","should","may","might","must","can","could","of","at",
        "by","for","with","about","against","between","into","through",
        "during","before","after","above","below","to","from","up","down",
        "in","out","on","off","over","under","again","further",
        "once","here","there","when","where","why","how","all","any",
        "both","each","few","more","most","other","some","such","only",
        "own","same","too","very","just","now","also","i","me",
        "my","myself","we","us","our","ours","ourselves","you","your",
        "yours","yourself","yourselves","he","him","his","himself","she",
        "her","hers","herself","it","its","itself","they","them","their",
        "theirs","themselves","what","which","who","whom","this","that",
        "these","those","said","says","say","get","got","make","made",
        "go","went","gone","come","came","see","saw","seen","know","knew",
        "one","two","three","first","last","new","year","years","day",
        "days","time","times","today","yesterday","tomorrow","ago","back"
    );

    /**
     * Extracts keywords from {@code text}.
     *
     * @param text any natural-language text (market question, article title, etc.)
     * @return set of lowercase keyword tokens; empty set if text is null or blank
     */
    public Set<String> extract(String text) {
        if (text == null || text.isBlank()) return Set.of();
        Set<String> keywords = new HashSet<>();
        for (String token : text.toLowerCase().split("[^a-z0-9]+")) {
            if (token.length() >= 3 && !STOP_WORDS.contains(token)) {
                keywords.add(token);
            }
        }
        return keywords;
    }
}
