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

package com.linecorp.armeria.internal.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
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
    void abortReleasesLastProducedUnsubscribedRequest() {
        final Supplier<StreamMessage<? extends HttpObject>> factory =
                () -> StreamMessage.of(HttpData.ofUtf8("body"));
        final ReproducibleHttpRequestDuplicator dup =
                new ReproducibleHttpRequestDuplicator(HEADERS, factory);

        final HttpRequest produced = dup.duplicate();
        // The produced request was never subscribed (e.g. endpoint selection threw); abort() must
        // tear it down so its body is not leaked.
        final RuntimeException cause = new RuntimeException("cleanup");
        dup.abort(cause);
        assertThat(produced.whenComplete()).isCompletedExceptionally();
    }
}
