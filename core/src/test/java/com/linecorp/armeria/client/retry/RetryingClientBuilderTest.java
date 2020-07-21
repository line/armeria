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

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpResponse;

class RetryingClientBuilderTest {
    @Test
    void buildWithMaxContentLength() {
        final RetryRuleWithContent<HttpResponse> rule =
                RetryRuleWithContent.onResponse((unused1, unused2) -> null);

        RetryingClient.builder(rule, 1);
        RetryingClient.builder(rule, 10);

        assertThatThrownBy(() -> RetryingClient.builder(rule, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxContentLength:");

        assertThatThrownBy(() -> RetryingClient.builder(rule, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxContentLength:");
    }
}
