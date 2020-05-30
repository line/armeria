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

package com.linecorp.armeria.client.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

class RetryRuleTest {

    @Test
    void testOf() {
        final RetryRule retryRule = RetryRule.onException();
        final RetryRule derived = RetryRule.of(retryRule);
        assertThat(derived).isEqualTo(retryRule);

        assertThatThrownBy(() -> RetryRule.of(ImmutableList.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("can't be empty.");

        assertThatThrownBy(RetryRule::of)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("can't be empty.");
    }
}
