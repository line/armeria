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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.stream.CancelledSubscriptionException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.ImmediateEventExecutor;

class CompletableHttpResponseTest {

    @Test
    void cancellationPropagatesToUpstream() {
        final CompletableHttpResponse res = HttpResponse.defer();
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

        await().untilAsserted(() -> assertThat(res.isCompletedExceptionally()).isTrue());
        assertThatExceptionOfType(ExecutionException.class).isThrownBy(res::get)
                                                           .havingCause()
                                                           .isInstanceOf(CancelledSubscriptionException.class);
    }

    @Test
    void shouldAbortLateResponse() {
        final CompletableHttpResponse res = HttpResponse.defer();
        res.complete(HttpResponse.of(HttpStatus.OK));
        final ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(10);
        buf.writeInt(1);
        final HttpResponse lateResponse =
                HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT, HttpData.wrap(buf));
        res.complete(lateResponse);

        assertThatThrownBy(() -> lateResponse.whenComplete().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("upstream set already");
        assertThat(buf.refCnt()).isZero();
    }

    @Test
    void shouldAbortResponseAfterCancellation() {
        final CompletableHttpResponse res = HttpResponse.defer();
        res.completeExceptionally(new ClosedSessionException("closed"));
        final ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(10);
        buf.writeInt(1);
        final HttpResponse lateResponse =
                HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT, HttpData.wrap(buf));
        res.complete(lateResponse);

        assertThatThrownBy(() -> lateResponse.whenComplete().join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseInstanceOf(ClosedSessionException.class)
                .hasRootCauseMessage("closed");
        assertThat(buf.refCnt()).isZero();
    }

    @Test
    void abortionShouldCompleteResponse() {
        final CompletableHttpResponse res = HttpResponse.defer();
        final ClosedSessionException cause = new ClosedSessionException("closed");
        res.abort(cause);
        assertThatThrownBy(() -> res.whenComplete().join())
                .isInstanceOf(CompletionException.class)
                .hasCause(cause);
    }

    @Test
    void shouldPropagateAbortCause() {
        final CompletableHttpResponse res = HttpResponse.defer();
        final ClosedSessionException cause = new ClosedSessionException("closed");
        res.abort(cause);
        final HttpResponse actual = HttpResponse.of(HttpStatus.OK);
        res.complete(actual);
        assertThatThrownBy(() -> actual.whenComplete().join())
                .isInstanceOf(CompletionException.class)
                .hasCause(cause);
    }

    @Test
    void subscribeBeforeComplete() {
        final List<HttpObject> accumulator = new ArrayList<>();
        final CompletableHttpResponse res = HttpResponse.defer();
        final AtomicBoolean subscribed = new AtomicBoolean();
        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicReference<Subscription> subscriptionRef = new AtomicReference<>();
        res.subscribe(new Subscriber<HttpObject>() {
            @Override
            public void onSubscribe(Subscription s) {
                subscribed.set(true);
                subscriptionRef.set(s);
                s.request(3);
            }

            @Override
            public void onNext(HttpObject httpObject) {
                accumulator.add(httpObject);
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {
                completed.set(true);
            }
        }, ImmediateEventExecutor.INSTANCE);

        // Should call onSubscribe() immediately even though upstream is not completed.
        assertThat(subscribed).isTrue();

        final HttpResponseWriter writer = HttpResponse.streaming();
        res.complete(writer);
        assertThat(accumulator).isEmpty();
        final HttpHeaders first = HttpHeaders.of("foo", "bar");
        writer.write(first);
        assertThat(accumulator).hasSize(1);
        final HttpData second = HttpData.ofUtf8("1");
        writer.write(second);
        assertThat(accumulator).hasSize(2);
        final HttpData third = HttpData.ofUtf8("2");
        writer.write(third);
        assertThat(accumulator).hasSize(3);

        final HttpData forth = HttpData.ofUtf8("3");
        writer.write(forth);
        assertThat(accumulator).hasSize(3);
        writer.close();
        // Drain a remaining element
        subscriptionRef.get().request(1);
        assertThat(completed).isTrue();
        assertThat(accumulator).containsExactly(first, second, third, forth);
    }
}
