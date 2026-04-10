package com.polysign.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Binds the {@code polysign.pollers.rss.feeds} YAML list to a typed {@code List<String>}.
 *
 * <p>{@code @Value} cannot bind a YAML list to {@code List<String>} — it only handles
 * scalar properties and comma-separated strings. {@code @ConfigurationProperties}
 * with constructor binding handles YAML lists correctly.
 */
@ConfigurationProperties(prefix = "polysign.pollers.rss")
public record RssProperties(List<String> feeds) {

    public RssProperties {
        if (feeds == null) {
            feeds = List.of();
        }
    }
}
