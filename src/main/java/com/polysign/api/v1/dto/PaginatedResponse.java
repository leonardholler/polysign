package com.polysign.api.v1.dto;

import java.util.List;

/**
 * Standard paginated envelope for all {@code /api/v1} list endpoints.
 *
 * @param <T> the data item type
 */
public record PaginatedResponse<T>(
        List<T> data,
        Pagination pagination,
        Meta meta
) {
    public record Pagination(String cursor, boolean hasMore) {}

    public record Meta(String requestedAt, String clientName) {}

    public static <T> PaginatedResponse<T> of(List<T> data, String cursor, boolean hasMore,
                                               String requestedAt, String clientName) {
        return new PaginatedResponse<>(data, new Pagination(cursor, hasMore), new Meta(requestedAt, clientName));
    }
}
