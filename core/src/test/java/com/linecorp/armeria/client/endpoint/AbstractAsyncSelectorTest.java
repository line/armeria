/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.client.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.annotation.Nullable;

class AbstractAsyncSelectorTest {

    @Test
    void basicCase() {
        final MySelector mySelector = new MySelector();
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        assertThat(mySelector.selectNow(ctx)).isNull();
        final CompletableFuture<String> cf =
                mySelector.select(ctx, CommonPools.workerGroup().next(), Long.MAX_VALUE);
        assertThat(cf).isNotDone();

        final String hello = "Hello";
        mySelector.value = hello;
        assertThat(cf).isNotDone();

        mySelector.refresh();
        await().untilAsserted(() -> assertThat(cf).isDone().isCompletedWithValue(hello));
    }

    @Test
    void timeout() {
        final MySelector mySelector = new MySelector();
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        assertThat(mySelector.selectNow(ctx)).isNull();
        final CompletableFuture<String> cf =
                mySelector.select(ctx, CommonPools.workerGroup().next(), 10);
        assertThat(cf).isNotDone();

        await().atLeast(10, TimeUnit.MILLISECONDS)
               .atMost(30, TimeUnit.MILLISECONDS)
               .pollInterval(10, TimeUnit.MILLISECONDS)
               .untilAsserted(() -> assertThat(cf).isDone().isCompletedWithValue(null));
    }

    @Test
    void zeroTimeout() {
        final MySelector mySelector = new MySelector();
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        assertThat(mySelector.selectNow(ctx)).isNull();
        final CompletableFuture<String> cf =
                mySelector.select(ctx, CommonPools.workerGroup().next(), 0);
        assertThat(cf).isDone().isCompletedWithValue(null);
    }

    private static class MySelector extends AbstractAsyncSelector<String> {

        @Nullable
        private String value;

        @Override
        protected String selectNow(ClientRequestContext ctx) {
            return value;
        }
    }
}
