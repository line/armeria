/*
 * Copyright 2023 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.util.Exceptions;

final class HttpResponseUtil {

    /**
     * Creates a new HTTP response that delegates to the {@link HttpResponse} produced by the specified
     * {@link CompletableFuture}. If the specified {@link CompletableFuture} fails, the returned response
     * will be closed with the same cause as well.
     *
     * @param future the {@link CompletableFuture} which will produce the actual {@link HttpResponse}
     */
    static HttpResponse createHttpResponseFrom(CompletableFuture<? extends HttpResponse> future) {
        requireNonNull(future, "future");

        if (future.isDone()) {
            if (!future.isCompletedExceptionally()) {
                return future.getNow(null);
            }

            try {
                future.join();
                // Should never reach here.
                throw new Error();
            } catch (Throwable cause) {
                return HttpResponse.ofFailure(Exceptions.peel(cause));
            }
        }

        final DeferredHttpResponse res = new DeferredHttpResponse();
        res.delegateWhenComplete(future);
        return res;
    }

    private HttpResponseUtil() {}
}
