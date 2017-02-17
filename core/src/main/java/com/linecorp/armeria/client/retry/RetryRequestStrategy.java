/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
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
    /**
     * A {@link RetryRequestStrategy} that defines a retry request should not be performed.
     */
    static <I extends Request, O extends Response> RetryRequestStrategy<I, O> never() {
        return new RetryRequestStrategy<I, O>() {
            @Override
            public boolean shouldRetry(I request, O response) {
                return false;
            }

            @Override
            public boolean shouldRetry(I request, Throwable throwable) {
                return false;
            }
        };
    }

    /**
     * Returns whether a request should be retried according to the given request and reponse.
     */
    boolean shouldRetry(I request, O response);

    /**
     * Returns whether an exception should be retried according to the given request and reponse.
     */
    default boolean shouldRetry(I request, Throwable thrown) {
        return true;
    }

}
