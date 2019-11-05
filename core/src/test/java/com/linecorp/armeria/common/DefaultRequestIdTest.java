/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DefaultRequestIdTest {

    @Test
    void basic() {
        final RequestId id = RequestId.random();
        assertThat(id).isInstanceOf(DefaultRequestId.class);
        assertThat(id.text()).hasSize(16);
        assertThat(id.shortText()).hasSize(8);
        assertThat(id.toString()).isEqualTo(id.text());
    }

    @Test
    void textWithoutLeadingZero() {
        final RequestId id = RequestId.of(0x123456789ABCDEF0L);
        assertThat(id.text()).isEqualTo("123456789abcdef0");
        assertThat(id.shortText()).isEqualTo("12345678");
    }

    @Test
    void textWithLeadingZero() {
        final RequestId id = RequestId.of(0x0FEDCBA987654321L);
        assertThat(id.text()).isEqualTo("0fedcba987654321");
        assertThat(id.shortText()).isEqualTo("0fedcba9");
    }

    @Test
    void cache() {
        final RequestId id = RequestId.random();
        final String longText = id.text();
        assertThat(id.text()).isSameAs(longText);
        final String shortText = id.shortText();
        assertThat(id.shortText()).isSameAs(shortText);
    }

    @Test
    void equality() {
        assertThat(new DefaultRequestId(1)).isEqualTo(new DefaultRequestId(1));
        assertThat(new DefaultRequestId(2)).isNotEqualTo(new DefaultRequestId(3));
    }

    @Test
    void hash() {
        assertThat(new DefaultRequestId(1).hashCode()).isEqualTo(1);
    }
}
