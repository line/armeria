/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.client.retry;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpResponse;

class RetryingClientBuilderTest {

    @Test
    void cannotSetContentPreviewLengthWhenRetryStrategyIsUsed() {
        final RetryRule rule = (ctx, cause) -> CompletableFuture.completedFuture(RetryDecision.noRetry());
        assertThatThrownBy(() -> RetryingClient.builder(rule).contentPreviewLength(1024))
                .isExactlyInstanceOf(IllegalStateException.class);
    }

    @Test
    void contentPreviewLengthCannotBeZero() {
        final RetryRuleWithContent<HttpResponse> rule =
                (ctx, response, cause) -> response.aggregate().handle((unused1, unused2) -> null);
        assertThatThrownBy(() -> RetryingClient.builder(rule).contentPreviewLength(0))
                .isExactlyInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildWithMaxContentLength() {
        final RetryRuleWithContent<HttpResponse> rule =
                RetryRuleWithContent.onResponse((unused1, unused2) -> null);

        RetryingClient.builder(rule, 1);
        RetryingClient.builder(rule).contentPreviewLength(10);

        assertThatThrownBy(() -> RetryingClient.builder(rule, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxContentLength: -1 (expected: > 0)");
        assertThatThrownBy(() -> RetryingClient.builder(rule, 1).contentPreviewLength(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxContentLength is already set by");
    }
}
