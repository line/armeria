/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.DefaultHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.stream.AbortedStreamException;

import io.netty.buffer.PooledByteBufAllocator;

@RunWith(Parameterized.class)
public class DefaultHttpRequestTest {

    @Rule
    public TestRule globalTimeout = new DisableOnDebug(new Timeout(10, TimeUnit.SECONDS));

    @Parameters(name = "{index}: executorSpecified={0}, withPooledObjects={1}")
    public static Collection<Boolean[]> parameters() {
        return ImmutableList.of(
                new Boolean[] { true, true },
                new Boolean[] { true, false },
                new Boolean[] { false, true },
                new Boolean[] { false, false });
    }

    private final boolean executorSpecified;
    private final boolean withPooledObjects;

    public DefaultHttpRequestTest(boolean executorSpecified, boolean withPooledObjects) {
        this.executorSpecified = executorSpecified;
        this.withPooledObjects = withPooledObjects;
    }

    /**
     * The aggregation future must be completed even if the request being aggregated has been aborted.
     */
    @Test
    public void abortedAggregation() {
        final Thread mainThread = Thread.currentThread();
        final DefaultHttpRequest req = new DefaultHttpRequest(HttpHeaders.of(HttpMethod.GET, "/foo"));
        final CompletableFuture<AggregatedHttpMessage> future;

        // Practically same execution, but we need to test the both case due to code duplication.
        if (executorSpecified) {
            if (withPooledObjects) {
                future = req.aggregateWithPooledObjects(
                        CommonPools.workerGroup().next(), PooledByteBufAllocator.DEFAULT);
            } else {
                future = req.aggregate(CommonPools.workerGroup().next());
            }
        } else {
            if (withPooledObjects) {
                future = req.aggregateWithPooledObjects(PooledByteBufAllocator.DEFAULT);
            } else {
                future = req.aggregate();
            }
        }

        final AtomicReference<Thread> callbackThread = new AtomicReference<>();

        assertThatThrownBy(() -> {
            final CompletableFuture<AggregatedHttpMessage> f =
                    future.whenComplete((unused, cause) -> callbackThread.set(Thread.currentThread()));
            req.abort();
            f.join();
        }).hasCauseInstanceOf(AbortedStreamException.class);

        assertThat(callbackThread.get()).isNotSameAs(mainThread);
    }

    @Test
    public void splitHeaders() {
        final HttpRequestWriter req = HttpRequest.streaming(HttpMethod.GET, "/foo");
        req.write(HttpData.ofUtf8("foo"));
        req.write(HttpHeaders.of(HttpHeaderNames.of("a"), "b"));
        req.write(HttpHeaders.of(HttpHeaderNames.of("c"), "d"));
        req.close();

        final AggregatedHttpMessage aggregated = req.aggregate().join();
        // Request headers
        assertThat(aggregated.headers()).isEqualTo(
                HttpHeaders.of(HttpMethod.GET, "/foo")
                           .add(HttpHeaderNames.CONTENT_LENGTH, "3"));
        // Content
        assertThat(aggregated.contentUtf8()).isEqualTo("foo");
        // Trailing headers
        assertThat(aggregated.trailingHeaders()).isEqualTo(
                HttpHeaders.of(HttpHeaderNames.of("a"), "b", HttpHeaderNames.of("c"), "d"));
    }
}
