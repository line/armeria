/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.stream.StreamMessage;

class ReproducibleHttpRequestDuplicatorTest {

    private static final RequestHeaders HEADERS = RequestHeaders.of(HttpMethod.POST, "/upload");

    @Test
    void everyDuplicateProducesAFreshBody() {
        final AtomicInteger calls = new AtomicInteger();
        final Supplier<StreamMessage<? extends HttpObject>> factory = () -> {
            calls.incrementAndGet();
            return StreamMessage.of(HttpData.ofUtf8("body"));
        };
        final ReproducibleHttpRequestDuplicator dup =
                new ReproducibleHttpRequestDuplicator(HEADERS, factory);

        // Even the first duplicate() invokes the factory — the caller's request is never reused.
        final HttpRequest first = dup.duplicate();
        assertThat(calls).hasValue(1);
        final HttpRequest second = dup.duplicate();
        assertThat(calls).hasValue(2);
        assertThat(first).isNotSameAs(second);
        assertThat(first.headers().method()).isEqualTo(HttpMethod.POST);
    }

    @Test
    void duplicateWithHeadersOverridesHeaders() {
        final Supplier<StreamMessage<? extends HttpObject>> factory =
                () -> StreamMessage.of(HttpData.ofUtf8("body"));
        final ReproducibleHttpRequestDuplicator dup =
                new ReproducibleHttpRequestDuplicator(HEADERS, factory);

        final RequestHeaders overridden = RequestHeaders.of(HttpMethod.POST, "/upload", "x-attempt", "1");
        final HttpRequest req = dup.duplicate(overridden);
        assertThat(req.headers().get("x-attempt")).isEqualTo("1");
    }

    @Test
    void factoryThrowingPropagates() {
        final Supplier<StreamMessage<? extends HttpObject>> factory = () -> {
            throw new IllegalStateException("cannot reproduce body");
        };
        final ReproducibleHttpRequestDuplicator dup =
                new ReproducibleHttpRequestDuplicator(HEADERS, factory);

        // Fail fast: the exception propagates so the client can terminate the request.
        assertThatThrownBy(dup::duplicate).isInstanceOf(IllegalStateException.class)
                                          .hasMessageContaining("cannot reproduce body");
    }

    @Test
    void factoryReturningNullThrows() {
        final Supplier<StreamMessage<? extends HttpObject>> factory = () -> null;
        final ReproducibleHttpRequestDuplicator dup =
                new ReproducibleHttpRequestDuplicator(HEADERS, factory);

        assertThatThrownBy(dup::duplicate).isInstanceOf(NullPointerException.class);
    }

    @Test
    void duplicateAfterCloseThrows() {
        final Supplier<StreamMessage<? extends HttpObject>> factory =
                () -> StreamMessage.of(HttpData.ofUtf8("body"));
        final ReproducibleHttpRequestDuplicator dup =
                new ReproducibleHttpRequestDuplicator(HEADERS, factory);

        dup.duplicate();
        dup.close();
        // StreamMessageDuplicator contract: duplicate() after close() must raise IllegalStateException.
        assertThatThrownBy(dup::duplicate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void duplicateAfterAbortTearsDownProducedBodyWithCause() {
        // The instance lock guards a duplicate() that races an abort(cause): the just-produced body
        // must be torn down with the remembered cause (so an open-file body is released) and duplicate()
        // must throw. Covers the abortCause-propagation branch that the close() variant above does not.
        final List<StreamMessage<HttpObject>> produced = new ArrayList<>();
        final Supplier<StreamMessage<? extends HttpObject>> factory = () -> {
            final StreamMessage<HttpObject> body = StreamMessage.of(HttpData.ofUtf8("body"));
            produced.add(body);
            return body;
        };
        final ReproducibleHttpRequestDuplicator dup =
                new ReproducibleHttpRequestDuplicator(HEADERS, factory);

        final RuntimeException cause = new RuntimeException("cleanup");
        dup.abort(cause);
        // duplicate() still runs the factory (outside the lock), then observes the aborted state, tears
        // the just-produced body down with the cause, and throws.
        assertThatThrownBy(dup::duplicate).isInstanceOf(IllegalStateException.class);
        assertThat(produced).hasSize(1);
        assertThat(produced.get(0).whenComplete()).isCompletedExceptionally();
        assertThatThrownBy(() -> produced.get(0).whenComplete().join())
                .isInstanceOf(CompletionException.class)
                .hasRootCause(cause);
    }

    @Test
    void concurrentAbortDuringDuplicateTearsDownProducedBody() throws Exception {
        // Deterministically reproduce the interleave the instance lock exists for: a caller thread is
        // inside duplicate() (the factory has produced a body but the child is not yet registered) while
        // the event-loop thread calls abort(cause). The factory blocks on a barrier until abort() has
        // fully completed, so duplicate() is guaranteed to observe the aborted state under the lock,
        // tear the just-produced body down with the cause, and throw. A regression that dropped
        // synchronized or registered the child outside the lock would leak the produced body here.
        final CyclicBarrier factoryEntered = new CyclicBarrier(2);
        final CountDownLatch abortDone = new CountDownLatch(1);
        final List<StreamMessage<HttpObject>> produced = new CopyOnWriteArrayList<>();
        final Supplier<StreamMessage<? extends HttpObject>> factory = () -> {
            final StreamMessage<HttpObject> body = StreamMessage.of(HttpData.ofUtf8("body"));
            produced.add(body);
            try {
                // Signal that the body has been produced, then wait until abort() has finished before
                // duplicate() proceeds to the synchronized closed-state check.
                factoryEntered.await(10, TimeUnit.SECONDS);
                abortDone.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return body;
        };
        final ReproducibleHttpRequestDuplicator dup =
                new ReproducibleHttpRequestDuplicator(HEADERS, factory);
        final RuntimeException cause = new RuntimeException("cleanup");

        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final Future<Throwable> duplicateResult = executor.submit(() -> {
                try {
                    dup.duplicate();
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });

            factoryEntered.await(10, TimeUnit.SECONDS);
            dup.abort(cause);
            abortDone.countDown();

            assertThat(duplicateResult.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(produced).hasSize(1);
            assertThat(produced.get(0).whenComplete()).isCompletedExceptionally();
            assertThatThrownBy(() -> produced.get(0).whenComplete().join())
                    .isInstanceOf(CompletionException.class)
                    .hasRootCause(cause);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void abortReleasesAllOutstandingUnsubscribedRequests() {
        final Supplier<StreamMessage<? extends HttpObject>> factory =
                () -> StreamMessage.of(HttpData.ofUtf8("body"));
        final ReproducibleHttpRequestDuplicator dup =
                new ReproducibleHttpRequestDuplicator(HEADERS, factory);

        // Multiple produced requests may be outstanding at once (e.g. hedging); abort() must tear
        // down every one of them, not just the most recently produced, so no body is leaked.
        final HttpRequest first = dup.duplicate();
        final HttpRequest second = dup.duplicate();
        final RuntimeException cause = new RuntimeException("cleanup");
        dup.abort(cause);
        assertThat(first.whenComplete()).isCompletedExceptionally();
        assertThat(second.whenComplete()).isCompletedExceptionally();
    }

    @Test
    void closeLeavesOutstandingRequestsActive() {
        final Supplier<StreamMessage<? extends HttpObject>> factory =
                () -> StreamMessage.of(HttpData.ofUtf8("body"));
        final ReproducibleHttpRequestDuplicator dup =
                new ReproducibleHttpRequestDuplicator(HEADERS, factory);

        final HttpRequest produced = dup.duplicate();
        // StreamMessageDuplicator contract: close() prevents further duplication but must not abort
        // requests that were already produced — they keep streaming until they complete on their own.
        dup.close();
        assertThat(produced.whenComplete()).isNotDone();
    }

    @Test
    void completedRequestIsUntrackedSoAbortDoesNotAffectIt() {
        final Supplier<StreamMessage<? extends HttpObject>> factory =
                () -> StreamMessage.of(HttpData.ofUtf8("body"));
        final ReproducibleHttpRequestDuplicator dup =
                new ReproducibleHttpRequestDuplicator(HEADERS, factory);

        // Draining a produced request completes it; it is then removed from the tracked set so the set
        // does not grow unbounded and a later abort() leaves the already-completed request untouched.
        final HttpRequest produced = dup.duplicate();
        produced.aggregate().join();
        // Wait for the request's own completion, which may resolve slightly after the aggregate future
        // (the completion callback fires on the event loop); join() makes this deterministic rather
        // than asserting on a completion that may not have fired yet.
        produced.whenComplete().join();
        assertThat(produced.whenComplete()).isCompleted();

        dup.abort(new RuntimeException("cleanup"));
        assertThat(produced.whenComplete()).isCompleted()
                                           .isNotCompletedExceptionally();
    }
}
