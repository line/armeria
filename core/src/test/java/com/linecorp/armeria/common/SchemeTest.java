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
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Optional;

import org.assertj.core.api.Condition;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link Scheme}.
 */
class SchemeTest {

    @Test
    public void tryParse_null() {
        assertThat(Scheme.tryParse(null)).isEmpty();
    }

    @Test
    void tryParse_add_none() {
        final Optional<Scheme> got = Scheme.tryParse("http");
        final Condition<Scheme> condition = new Condition<>(scheme ->
                                                                    (scheme.sessionProtocol() ==
                                                                     SessionProtocol.HTTP) &&
                                                                    (scheme.serializationFormat() ==
                                                                     SerializationFormat.NONE),
                                                            "none+http");
        assertThat(got).hasValueSatisfying(condition);
    }

    @Test
    void tryParse_with_none() {
        final Optional<Scheme> got = Scheme.tryParse("http+none");
        final Condition<Scheme> condition = new Condition<>(scheme ->
                                                                    (scheme.sessionProtocol() ==
                                                                     SessionProtocol.HTTP) &&
                                                                    (scheme.serializationFormat() ==
                                                                     SerializationFormat.NONE),
                                                            "none+http");
        assertThat(got).hasValueSatisfying(condition);
    }

    @Test
    void parse_null() {
        assertThatCode(() -> Scheme.parse(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void parse_exception() {
        assertThatCode(() -> Scheme.parse("http+blah")).isInstanceOf(IllegalArgumentException.class)
                                                       .hasMessageContaining("scheme: http+blah");
    }
}
