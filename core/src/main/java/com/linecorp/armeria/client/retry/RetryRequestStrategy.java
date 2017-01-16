package com.linecorp.armeria.client.retry;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

/**
 * Provides custom condition on whether a failed request should be retried.
 * @param <I> the request type
 * @param <O> the response type
 */
@FunctionalInterface
public interface RetryRequestStrategy<I extends Request, O extends Response> {

    static <I extends Request, O extends Response> RetryRequestStrategy<I, O> always() {
        return (unused1, unused2, unused3) -> true;
    }

    static <I extends Request, O extends Response> RetryRequestStrategy<I, O> never() {
        return (unused1, unused2, unused3) -> false;
    }

    /**
     * Returns whether a request should be retried according to the given request and reponse.
     */
    boolean shouldRetry(I request, O response, Throwable thrown);

}
