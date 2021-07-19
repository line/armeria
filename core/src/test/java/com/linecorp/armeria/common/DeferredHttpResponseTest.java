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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.stream.CancelledSubscriptionException;

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

        await().untilAsserted(() -> assertThat(future.isCompletedExceptionally()).isTrue());
        assertThatExceptionOfType(ExecutionException.class).isThrownBy(future::get)
                .havingCause().isInstanceOf(CancelledSubscriptionException.class);
    }
}
