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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.stream.DeferredStreamMessage;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.testing.AnticipatedException;

import io.netty.util.concurrent.ImmediateEventExecutor;

class DeferredHttpRequestTest {

    @Test
    void setStreamMessageAfterSubscribe() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/");
        final CompletableFuture<StreamMessage<HttpObject>> future = new CompletableFuture<>();

        final HttpRequest req = HttpRequest.from(headers, future);
        assertThat(req).isInstanceOf(DeferredStreamMessage.class);
        final CompletableFuture<List<HttpObject>> collectFuture = req.collect();

        final StreamMessage<HttpObject> data = StreamMessage.of(HttpData.ofUtf8("foo"), HttpHeaders.of());
        future.complete(data);
        final List<HttpObject> collected = collectFuture.join();
        assertThat(collected).hasSize(2);
        assertThat(collected.get(0)).isEqualTo(HttpData.ofUtf8("foo"));
        assertThat(collected.get(1)).isEqualTo(HttpHeaders.of());
    }

    @Test
    void abortedStreamMessage() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/");
        final CompletableFuture<StreamMessage<HttpObject>> future = new CompletableFuture<>();

        final HttpRequest req = HttpRequest.from(headers, future);
        assertThat(req).isInstanceOf(DeferredStreamMessage.class);
        final CompletableFuture<List<HttpObject>> collectFuture = req.collect();

        final StreamMessage<HttpObject> data = StreamMessage.aborted(new AnticipatedException());
        future.complete(data);
        assertThatThrownBy(collectFuture::join).hasCauseInstanceOf(AnticipatedException.class);
    }

    @Test
    void cancellationPropagatesToUpstream() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/");
        final CompletableFuture<StreamMessage<HttpObject>> future = new CompletableFuture<>();
        final DeferredHttpRequest req = new DeferredHttpRequest(headers);
        req.delegateWhenComplete(future);
        req.subscribe(new Subscriber<HttpObject>() {
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

    @Test
    void shouldNotDelegateIfCompletedAlready() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/");
        final StreamMessage<HttpObject> originalStream = StreamMessage.of(HttpData.ofUtf8("foo"));

        final CompletableFuture<StreamMessage<HttpObject>> completedFuture =
                UnmodifiableFuture.completedFuture(originalStream);

        final HttpRequest req = HttpRequest.from(headers, completedFuture);
        assertThat(req).isNotInstanceOf(DeferredStreamMessage.class);
    }

    @Test
    void shouldNotDelegateIfCompletedExceptionallyAlready() {
        final Exception originalCause = new Exception();

        final CompletableFuture<StreamMessage<HttpObject>> completedFuture = new CompletableFuture<>();
        completedFuture.completeExceptionally(originalCause);

        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/");
        final HttpRequest req = HttpRequest.from(headers, completedFuture);
        assertThat(req).isNotInstanceOf(DeferredStreamMessage.class);
        assertThatThrownBy(() -> req.collect().join()).hasCause(originalCause);
    }
}
