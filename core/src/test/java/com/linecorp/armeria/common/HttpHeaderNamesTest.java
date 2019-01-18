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

import org.junit.Test;

import io.netty.util.AsciiString;

public class HttpHeaderNamesTest {

    @Test
    public void testOfAsciiString() {
        // Should produce a lower-cased AsciiString.
        final AsciiString mixedCased = AsciiString.of("Foo");
        assertThat((Object) HttpHeaderNames.of(mixedCased)).isNotSameAs(mixedCased);
        assertThat(HttpHeaderNames.of(mixedCased).toString()).isEqualTo("foo");

        // Should not produce a new instance for an AsciiString that's already lower-cased.
        final AsciiString lowerCased = AsciiString.of("foo");
        assertThat((Object) HttpHeaderNames.of(lowerCased)).isSameAs(lowerCased);

        // Should reuse known header name instances.
        assertThat((Object) HttpHeaderNames.of(AsciiString.of("date"))).isSameAs(HttpHeaderNames.DATE);
    }

    @Test
    public void testOfCharSequence() {
        // Should produce a lower-cased AsciiString.
        assertThat((Object) HttpHeaderNames.of("Foo")).isEqualTo(AsciiString.of("foo"));

        // Should reuse known header name instances.
        assertThat((Object) HttpHeaderNames.of("date")).isSameAs(HttpHeaderNames.DATE);
    }
}
