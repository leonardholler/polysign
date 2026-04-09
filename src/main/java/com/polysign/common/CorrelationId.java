package com.polysign.common;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Thin MDC helper that carries a correlation ID through a poll/job cycle.
 *
 * <p>Usage:
 * <pre>
 *   try (var ignored = CorrelationId.set()) {
 *       // every log line inside this try-with-resources block will carry
 *       // the same correlationId — traceable from poll → detector → alert → notification
 *   }
 * </pre>
 *
 * The ID flows through the whole pipeline as an SLF4J MDC key named {@code correlationId}.
 * It is emitted in every JSON log line by logstash-logback-encoder.
 */
public final class CorrelationId {

    public static final String MDC_KEY = "correlationId";

    private CorrelationId() {}

    /**
     * Generates a new short correlation ID, sets it in the MDC, and returns an
     * {@link AutoCloseable} that clears the MDC entry on close.
     */
    public static AutoCloseable set() {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        MDC.put(MDC_KEY, id);
        return () -> MDC.remove(MDC_KEY);
    }

    /**
     * Sets a specific correlation ID (e.g. propagated from an SQS message attribute).
     */
    public static AutoCloseable set(String id) {
        MDC.put(MDC_KEY, id);
        return () -> MDC.remove(MDC_KEY);
    }

    /** Returns the current correlation ID, or {@code "none"} if not set. */
    public static String current() {
        String id = MDC.get(MDC_KEY);
        return id != null ? id : "none";
    }
}
