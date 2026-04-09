package com.polysign.common;

import java.util.Optional;
import java.util.function.Function;

/**
 * Lightweight discriminated union representing either a successful value or an error.
 *
 * <p>Used by pollers and processors to return results without throwing checked exceptions,
 * keeping callers free to choose how to handle failures (log + continue, re-throw, etc.).
 *
 * <pre>
 *   Result&lt;PriceSnapshot&gt; result = pricePoller.fetch(marketId);
 *   result.onSuccess(snapshot -> repo.save(snapshot))
 *         .onFailure(err -> log.warn("fetch failed", err));
 * </pre>
 *
 * @param <T> the success value type
 */
public sealed interface Result<T> permits Result.Success, Result.Failure {

    static <T> Result<T> success(T value) {
        return new Success<>(value);
    }

    static <T> Result<T> failure(Throwable error) {
        return new Failure<>(error);
    }

    boolean isSuccess();

    Optional<T> value();

    Optional<Throwable> error();

    default <R> Result<R> map(Function<T, R> mapper) {
        if (isSuccess()) {
            return Result.success(mapper.apply(value().orElseThrow()));
        }
        return Result.failure(error().orElseThrow());
    }

    record Success<T>(T val) implements Result<T> {
        public boolean isSuccess()           { return true;                  }
        public Optional<T> value()           { return Optional.of(val);      }
        public Optional<Throwable> error()   { return Optional.empty();      }
    }

    record Failure<T>(Throwable err) implements Result<T> {
        public boolean isSuccess()           { return false;                 }
        public Optional<T> value()           { return Optional.empty();      }
        public Optional<Throwable> error()   { return Optional.of(err);      }
    }
}
