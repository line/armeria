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

package com.linecorp.armeria.client.retry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpResponse;

class RetryConfigBuilderTest {

    @Test
    void responseTimeoutMillisForEachAttemptDefaultsToZeroWhenUnset() {
        // Regression test for https://github.com/line/armeria/issues/6385:
        // when responseTimeoutForEachAttempt()/responseTimeoutMillisForEachAttempt() is never called,
        // the per-attempt timeout must not silently fall back to Flags.defaultResponseTimeoutMillis()
        // (15 seconds), which used to cap any larger client/request-level responseTimeout.
        // 0 means "no separate per-attempt cap", deferring to the response timeout of the whole retry.
        final RetryConfig<HttpResponse> config =
                RetryConfig.builder(RetryRule.failsafe()).build();

        assertThat(config.responseTimeoutMillisForEachAttempt()).isZero();
    }

    @Test
    void responseTimeoutMillisForEachAttemptIsPreservedWhenExplicitlySet() {
        final RetryConfig<HttpResponse> config =
                RetryConfig.builder(RetryRule.failsafe())
                          .responseTimeoutMillisForEachAttempt(1234)
                          .build();

        assertThat(config.responseTimeoutMillisForEachAttempt()).isEqualTo(1234);
    }

    @Test
    void responseTimeoutForEachAttemptDurationIsPreservedWhenExplicitlySet() {
        final RetryConfig<HttpResponse> config =
                RetryConfig.builder(RetryRule.failsafe())
                          .responseTimeoutForEachAttempt(Duration.ofSeconds(20))
                          .build();

        assertThat(config.responseTimeoutMillisForEachAttempt()).isEqualTo(20_000);
    }
}
