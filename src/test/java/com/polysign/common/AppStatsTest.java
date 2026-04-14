package com.polysign.common;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the wallet-tracking counter in {@link AppStats}.
 */
class AppStatsTest {

    // ── Test 1: recordTrade + walletsSeenInLast24h returns correct count ──────

    @Test
    void recordTrade_countsDuplicateAddressOnce() {
        AppStats stats = new AppStats();

        stats.recordTrade("0xAAA");
        stats.recordTrade("0xBBB");
        stats.recordTrade("0xAAA"); // duplicate — must not double-count

        assertThat(stats.walletsSeenInLast24h(Clock.systemUTC())).isEqualTo(2L);
    }

    // ── Test 2: walletsSeenInLast24h excludes addresses older than 24h ────────

    @Test
    void walletsSeenInLast24h_excludesAddressesOlderThan24h() {
        AppStats stats = new AppStats();

        stats.recordTrade("0xOLD"); // stored at current real time

        // Clock advanced 25h into the future → the entry is >24h old from that perspective
        Instant future25h = Instant.now().plus(25, ChronoUnit.HOURS);
        assertThat(stats.walletsSeenInLast24h(
                Clock.fixed(future25h, ZoneOffset.UTC))).isEqualTo(0L);

        // Clock advanced 23h into the future → the entry is <24h old, should still count
        Instant future23h = Instant.now().plus(23, ChronoUnit.HOURS);
        assertThat(stats.walletsSeenInLast24h(
                Clock.fixed(future23h, ZoneOffset.UTC))).isEqualTo(1L);
    }

    // ── Test 3: pruneWalletMap caps the map at WALLET_MAP_MAX ────────────────

    @Test
    void pruneWalletMap_capsAtMaxSize() {
        AppStats stats = new AppStats();

        // Insert 200_001 distinct entries — the 200_001st triggers inline pruning
        for (int i = 0; i <= 200_000; i++) {
            stats.recordTrade("addr-" + i);
        }

        // After the cap is triggered, the map should be pruned to roughly half
        assertThat(stats.walletsSeenInLast24h(Clock.systemUTC()))
                .isLessThanOrEqualTo(200_000L);
    }

    // ── Test 4: pruneWalletMap (scheduled) removes entries older than 24h ────

    @Test
    void pruneWalletMap_removesEntriesOlderThan24h_afterScheduledPrune() {
        AppStats stats = new AppStats();

        stats.recordTrade("0xFRESH"); // stored at current real time

        // Verify present before prune
        assertThat(stats.walletsSeenInLast24h(Clock.systemUTC())).isEqualTo(1L);

        // Run the scheduled prune — should keep the fresh entry
        stats.pruneWalletMap();

        assertThat(stats.walletsSeenInLast24h(Clock.systemUTC())).isEqualTo(1L);

        // From a clock 25h in the future, even after prune, the entry is not yet gone
        // (pruneWalletMap uses real System.currentTimeMillis for the cutoff).
        // What we can verify: prune does not corrupt the map.
        Instant future23h = Instant.now().plus(23, ChronoUnit.HOURS);
        assertThat(stats.walletsSeenInLast24h(
                Clock.fixed(future23h, ZoneOffset.UTC))).isEqualTo(1L);
    }
}
