/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.server.throttling;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletionStage;

import javax.annotation.Nullable;

import org.junit.Test;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;

public class RetryThrottlingStrategyTest {
    @Test
    public void testStrategy() {
        final TestRetryThrottlingStrategy strategy = new TestRetryThrottlingStrategy();
        assertThat(strategy.name())
                .isEqualTo("throttling-strategy-TestRetryThrottlingStrategy");
        assertThat(strategy.retryAfterSeconds())
                .isEqualTo("10");
        final ResponseHeaders headers = strategy.getResponseHeaders();
        assertThat(headers.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(headers.get(HttpHeaderNames.RETRY_AFTER)).isEqualTo("10");
    }

    private static class TestRetryThrottlingStrategy extends RetryThrottlingStrategy<HttpRequest> {
        @Nullable
        @Override
        protected String retryAfterSeconds() {
            return "10";
        }

        @Override
        public CompletionStage<Boolean> accept(ServiceRequestContext ctx, HttpRequest request) {
            return completedFuture(true);
        }
    }
}
