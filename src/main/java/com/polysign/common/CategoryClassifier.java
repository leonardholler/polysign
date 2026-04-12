package com.polysign.common;

import java.util.List;
import java.util.Map;

/**
 * Classifies a Polymarket market into one of a closed set of categories using
 * keyword matching against the question text and event slug.
 *
 * <p>The closed set is: politics | sports | crypto | economics | entertainment |
 * ai_tech | weather_science | elections_intl | current_events | other.
 *
 * <p>Rules are evaluated in declaration order; the first match wins.
 * Crypto is checked before elections_intl/politics to avoid "bitcoin" → politics.
 * elections_intl is checked before politics so that questions naming international leaders
 * (Modi, Trudeau, Netanyahu, …) or "EU parliament" are not subsumed into the generic
 * politics bucket. The four sub-categories (ai_tech, weather_science, elections_intl,
 * current_events) appear before the OTHER fallback and further partition questions that
 * previously fell into "other".
 * If no keyword matches, "other" is returned.
 */
public final class CategoryClassifier {

    public static final String POLITICS        = "politics";
    public static final String SPORTS          = "sports";
    public static final String CRYPTO          = "crypto";
    public static final String ECONOMICS       = "economics";
    public static final String ENTERTAINMENT   = "entertainment";
    public static final String AI_TECH         = "ai_tech";
    public static final String WEATHER_SCIENCE = "weather_science";
    public static final String ELECTIONS_INTL  = "elections_intl";
    public static final String CURRENT_EVENTS  = "current_events";
    public static final String OTHER           = "other";

    /**
     * Ordered rules — first match wins.
     * Keywords are checked via {@code text.contains(keyword)} on the lowercased
     * concatenation of question + event slug.
     */
    private static final List<Map.Entry<String, List<String>>> RULES = List.of(

        Map.entry(CRYPTO, List.of(
            "bitcoin", "btc", "ethereum", "eth", " crypto", "blockchain", "token",
            "defi", " nft", "solana", "dogecoin", "doge", "binance", "coinbase",
            "web3", "altcoin", "stablecoin", "usdc", "usdt", "xrp", "ripple",
            "litecoin", "ltc", "bnb", "polygon", "avalanche", "chainlink"
        )),

        // elections_intl before politics: international-leader names would otherwise
        // be caught by "minister", "parliament", "election" in the politics rule.
        Map.entry(ELECTIONS_INTL, List.of(
            "modi", "netanyahu", "erdogan", "lula", "milei", "meloni",
            "sunak", "trudeau", "albanese", "orban",
            "south korea election", "taiwan election", "india election",
            "canada election", "australia election", "mexico election",
            "brazil election", "japan election", "turkey election",
            "uk election", "eu parliament", "european parliament"
        )),

        Map.entry(POLITICS, List.of(
            "election", "president", "vote", "congress", "senate", "governor",
            "parliament", "minister", "government", "biden", "trump", "harris",
            "democrat", "republican", "nato", "ceasefire", "sanction",
            "ukraine", "north korea", "legislation", "supreme court",
            "treaty", "military", "coup", "referendum", "tariff bill",
            "xi jinping", "putin", "zelensky", "macron", "scholz"
        )),

        Map.entry(SPORTS, List.of(
            " nba", " nfl", " mlb", " nhl", " fifa", "soccer", "football",
            "basketball", "baseball", "hockey", "championship", "playoffs",
            "super bowl", "world cup", " ufc", "boxing", " tennis", " golf",
            " racing", "formula 1", "f1 ", "draft pick", "trade deadline",
            "stanley cup", "world series", "march madness", "wimbledon"
        )),

        Map.entry(ECONOMICS, List.of(
            "federal reserve", "interest rate", "inflation", " gdp", "recession",
            "stock market", "earnings report", "unemployment", "trade deficit",
            " cpi", " ppi", "payroll", "treasury yield", " ipo ", "mortgage rate",
            "rate hike", "rate cut", "fed meeting", "fomc", "dow jones", "s&p 500"
        )),

        Map.entry(ENTERTAINMENT, List.of(
            " movie", " film", " oscar", " grammy", " emmy", "box office",
            "netflix", "streaming", "celebrity", "music album", "concert",
            "gta vi", "gta 6", "game release", "tv show", "season finale",
            "box office", "ticket sales", "award show", "golden globe"
        )),

        Map.entry(AI_TECH, List.of(
            "openai", "chatgpt", " gpt-", "deepmind", "anthropic", " llm",
            "machine learning", "artificial intelligence", "spacex", "tesla",
            "nvidia", "semiconductor", "cybersecurity", "data breach",
            "self-driving", "autonomous vehicle", "humanoid robot"
        )),

        Map.entry(WEATHER_SCIENCE, List.of(
            "hurricane", "earthquake", "tornado", "wildfire", "drought", "blizzard",
            "tsunami", "typhoon", "climate change", "global warming", "nasa",
            " volcano", "famine", "pandemic", "covid", "space launch",
            "Nobel prize", "meteor", "comet", "solar storm"
        )),

        Map.entry(CURRENT_EVENTS, List.of(
            "lawsuit", "merger", "acquisition", "bankruptcy", "resignation",
            " protest", "union strike", "immigration", "refugee",
            "assassination", "hostage", "indicted", "convicted", "sentenced",
            "recall", "class action"
        ))
    );

    private CategoryClassifier() {}

    /**
     * @param question  the market question (may be null)
     * @param eventSlug the parent event slug (may be null)
     * @return one of the category constants defined in this class
     */
    public static String classify(String question, String eventSlug) {
        String text = ((question  == null ? "" : question)
                     + " "
                     + (eventSlug == null ? "" : eventSlug)).toLowerCase();

        for (var rule : RULES) {
            for (String keyword : rule.getValue()) {
                if (text.contains(keyword)) {
                    return rule.getKey();
                }
            }
        }
        return OTHER;
    }
}
