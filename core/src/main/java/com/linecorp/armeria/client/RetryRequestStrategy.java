package com.linecorp.armeria.client;

/**
 * Provides custom condition on whether a failed request should be retried.
 * @param <I> the request type
 * @param <O> the response type
 */
@FunctionalInterface
public interface RetryRequestStrategy<I, O> {
    /**
     * Returns whether a request should be retried according to the given request and reponse.
     */
    boolean shouldRetry(I request, O obj);

    static <I, O> RetryRequestStrategy<I, O> alwaysTrue() {
        return (unused1, unused2) -> true;
    }

    static <I, O> RetryRequestStrategy<I, O> alwaysFalse() {
        return (unused1, unused2) -> false;
    }
}
