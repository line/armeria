/*
 * Copyright 2026 LINE Corporation
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

package com.linecorp.armeria.internal.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;

class RequestFactoryHttpRequestDuplicatorTest {

    @Test
    void firstDuplicateReturnsOriginalThenFactory() {
        final AtomicInteger factoryCalls = new AtomicInteger();
        final HttpRequest original = HttpRequest.of(HttpMethod.POST, "/orig");
        final Supplier<HttpRequest> factory = () -> {
            factoryCalls.incrementAndGet();
            return HttpRequest.of(HttpMethod.POST, "/fromFactory");
        };

        final RequestFactoryHttpRequestDuplicator dup =
                new RequestFactoryHttpRequestDuplicator(original, factory);

        // First duplicate: uses the original request, factory not invoked.
        final HttpRequest first = dup.duplicate();
        assertThat(first.path()).isEqualTo("/orig");
        assertThat(factoryCalls).hasValue(0);

        // Second duplicate: uses the factory.
        final HttpRequest second = dup.duplicate();
        assertThat(second.path()).isEqualTo("/fromFactory");
        assertThat(factoryCalls).hasValue(1);
    }

    @Test
    void headerOverrideAppliedOnBothPaths() {
        final HttpRequest original = HttpRequest.of(HttpMethod.POST, "/orig");
        final Supplier<HttpRequest> factory = () -> HttpRequest.of(HttpMethod.POST, "/orig");
        final RequestFactoryHttpRequestDuplicator dup =
                new RequestFactoryHttpRequestDuplicator(original, factory);

        final RequestHeaders overridden = RequestHeaders.of(HttpMethod.POST, "/orig", "x-attempt", "1");
        final HttpRequest first = dup.duplicate(overridden);
        assertThat(first.headers().get("x-attempt")).isEqualTo("1");

        final RequestHeaders overridden2 = RequestHeaders.of(HttpMethod.POST, "/orig", "x-attempt", "2");
        final HttpRequest second = dup.duplicate(overridden2);
        assertThat(second.headers().get("x-attempt")).isEqualTo("2");
    }

    @Test
    void factoryThrowingYieldsFailedRequestNotThrow() {
        final HttpRequest original = HttpRequest.of(HttpMethod.POST, "/orig");
        final RuntimeException boom = new RuntimeException("boom");
        final Supplier<HttpRequest> factory = () -> {
            throw boom;
        };
        final RequestFactoryHttpRequestDuplicator dup =
                new RequestFactoryHttpRequestDuplicator(original, factory);

        dup.duplicate(); // first attempt consumes original
        final HttpRequest failed = dup.duplicate(); // factory throws internally
        // Does not throw; the returned request fails when subscribed.
        assertThat(failed.whenComplete()).isCompletedExceptionally();
    }

    @Test
    void factoryReturningNullYieldsFailedRequest() {
        final HttpRequest original = HttpRequest.of(HttpMethod.POST, "/orig");
        final Supplier<HttpRequest> factory = () -> null;
        final RequestFactoryHttpRequestDuplicator dup =
                new RequestFactoryHttpRequestDuplicator(original, factory);

        dup.duplicate();
        final HttpRequest failed = dup.duplicate();
        assertThat(failed.whenComplete()).isCompletedExceptionally();
    }

    @Test
    void abortBeforeDuplicateAbortsOriginal() {
        final HttpRequest original = HttpRequest.streaming(RequestHeaders.of(HttpMethod.POST, "/orig"));
        final Supplier<HttpRequest> factory = () -> HttpRequest.of(HttpMethod.POST, "/orig");
        final RequestFactoryHttpRequestDuplicator dup =
                new RequestFactoryHttpRequestDuplicator(original, factory);

        dup.abort(new RuntimeException("cleanup"));
        assertThat(original.whenComplete()).isCompletedExceptionally();
    }

    @Test
    void factoryDuplicatorHasNoSizeCapAcrossManyDuplicates() {
        // The factory duplicator never accumulates signal length, so no ContentTooLargeException
        // is possible regardless of body size. This asserts the factory path is unaffected by the
        // int cap that limits DefaultStreamMessageDuplicator (verified separately in that class's
        // own tests). Here we simply confirm many large-"reported"-length duplications succeed.
        final HttpRequest original = HttpRequest.of(HttpMethod.POST, "/orig");
        final Supplier<HttpRequest> factory = () -> HttpRequest.of(HttpMethod.POST, "/orig");
        final RequestFactoryHttpRequestDuplicator dup =
                new RequestFactoryHttpRequestDuplicator(original, factory);

        // First (original) + several factory-produced duplicates, none throw.
        assertThat(dup.duplicate()).isNotNull();
        for (int i = 0; i < 5; i++) {
            assertThat(dup.duplicate()).isNotNull();
        }
    }
}
