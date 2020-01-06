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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Test for {@link Scheme}.
 */
class SchemeTest {

    @Test
    public void tryParse_null() {
        assertThat(Scheme.tryParse(null)).isNull();
    }

    @Test
    void tryParse_add_none() {
        final Scheme scheme = Scheme.tryParse("http");
        assertThat(scheme).isNotNull();
        assertThat(scheme.sessionProtocol()).isEqualTo(SessionProtocol.HTTP);
        assertThat(scheme.serializationFormat()).isEqualTo(SerializationFormat.NONE);
    }

    @Test
    void tryParse_with_none() {
        final Scheme scheme = Scheme.tryParse("http+none");
        assertThat(scheme).isNotNull();
        assertThat(scheme.sessionProtocol()).isEqualTo(SessionProtocol.HTTP);
        assertThat(scheme.serializationFormat()).isEqualTo(SerializationFormat.NONE);
    }

    @Test
    void parse_null() {
        assertThatThrownBy(() -> Scheme.parse(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void parse_exception() {
        assertThatThrownBy(() -> Scheme.parse("http+blah")).isInstanceOf(IllegalArgumentException.class)
                                                           .hasMessageContaining("scheme: http+blah");
    }
}
