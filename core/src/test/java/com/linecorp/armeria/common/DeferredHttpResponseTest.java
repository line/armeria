/*
 * Copyright 2021 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.internal.common.AbortedHttpResponse;

import io.netty.util.concurrent.ImmediateEventExecutor;

class DeferredHttpResponseTest {

    @Test
    void cancellationPropagatesToUpstream() {
        final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        final DeferredHttpResponse res = new DeferredHttpResponse();
        res.delegateWhenComplete(future);
        res.subscribe(new Subscriber<HttpObject>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.cancel(); // Cancel immediately.
            }

            @Override
            public void onNext(HttpObject httpObject) {}

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {}
        }, ImmediateEventExecutor.INSTANCE);

        // Should not cancel the upstream CompletableFuture.
        // A HttpResponse could be leaked if it is set after completion.
        assertThat(future).isNotDone();
    }

    /**
     * When the given future is already complete, we should not wrap it with {@link DeferredHttpResponse}
     * but just return the response directly.
     */
    @Test
    void shouldNotDelegateIfCompletedAlready() {
        final HttpResponse originalRes = HttpResponse.of(200);

        // Because we don't expect users to always use `UnmodifiableFuture`.
        @SuppressWarnings("checkstyle:PreferUnmodifiableFuture")
        final CompletableFuture<HttpResponse> completedFuture = CompletableFuture.completedFuture(originalRes);

        final HttpResponse res1 = HttpResponse.of(completedFuture);
        final HttpResponse res2 = HttpResponse.of((CompletionStage<? extends HttpResponse>) completedFuture);
        assertThat(res1).isSameAs(originalRes);
        assertThat(res2).isSameAs(originalRes);
    }

    /**
     * When the given future is already complete exceptionally, we should not wrap it with
     * {@link DeferredHttpResponse} but just return the failed response directly.
     */
    @Test
    void shouldNotDelegateIfCompletedExceptionallyAlready() {
        final Exception originalCause = new Exception();

        final CompletableFuture<HttpResponse> completedFuture = new CompletableFuture<>();
        completedFuture.completeExceptionally(originalCause);

        final HttpResponse res1 = HttpResponse.of(completedFuture);
        final HttpResponse res2 = HttpResponse.of((CompletionStage<? extends HttpResponse>) completedFuture);
        assertThat(res1).isInstanceOf(AbortedHttpResponse.class);
        assertThat(res2).isInstanceOf(AbortedHttpResponse.class);
        assertThatThrownBy(() -> res1.collect().join()).hasCause(originalCause);
        assertThatThrownBy(() -> res2.collect().join()).hasCause(originalCause);
    }
}
