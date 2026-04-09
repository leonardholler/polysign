package com.polysign.common;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Thin wrapper around {@link Clock} to make time injectable / mockable in tests.
 *
 * <p>All "what time is it now?" calls throughout the application should use this
 * class rather than {@code Instant.now()} or {@code System.currentTimeMillis()} directly.
 * This makes detectors and pollers trivially testable with a fixed clock.
 */
@Component
public class AppClock {

    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private Clock clock = Clock.systemUTC();

    /** Returns the current instant. */
    public Instant now() {
        return Instant.now(clock);
    }

    /** Returns the current time as an ISO-8601 string (UTC, lexicographically sortable). */
    public String nowIso() {
        return ISO_FORMATTER.format(now());
    }

    /** Returns the current time as Unix epoch seconds. */
    public long nowEpochSeconds() {
        return now().getEpochSecond();
    }

    /**
     * Replaces the underlying clock — intended for tests only.
     * <pre>
     *   appClock.setClock(Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC));
     * </pre>
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }
}
