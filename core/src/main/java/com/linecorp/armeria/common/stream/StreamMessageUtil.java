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

package com.linecorp.armeria.common.stream;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.reactivestreams.Publisher;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.Exceptions;

final class StreamMessageUtil {

    /**
     * Creates a new {@link StreamMessage} that delegates to the {@link StreamMessage} produced by the specified
     * {@link CompletionStage}. If the specified {@link CompletionStage} fails, the returned
     * {@link StreamMessage} will be closed with the same cause as well.
     *
     * @param future the {@link CompletionStage} which will produce the actual {@link StreamMessage}
     */
    @UnstableApi
    static <T> StreamMessage<T> createStreamMessageFrom(
            CompletableFuture<? extends Publisher<? extends T>> future) {
        requireNonNull(future, "future");

        if (future.isDone()) {
            if (!future.isCompletedExceptionally()) {
                final Publisher<? extends T> publisher = future.getNow(null);
                return StreamMessage.of(publisher);
            }

            try {
                future.join();
                // Should never reach here.
                throw new Error();
            } catch (Throwable cause) {
                return StreamMessage.aborted(Exceptions.peel(cause));
            }
        }

        final DeferredStreamMessage<T> deferred = new DeferredStreamMessage<>();
        //noinspection unchecked
        deferred.delegateOnCompletion((CompletionStage<? extends Publisher<T>>) future);
        return deferred;
    }

    private StreamMessageUtil() {}
}
