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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.common.MediaTypeTest.getConstantFields;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.net.HttpHeaders;

import io.netty.util.AsciiString;

class HttpHeaderNamesTest {

    @Test
    void httpHeaderNamesContainsAllFieldsInHttpHeadersInGuava() throws Exception {
        final List<String> headerNames = names(getConstantFields(HttpHeaderNames.class, AsciiString.class));
        final List<String> guavaHttpHeaders = names(getConstantFields(HttpHeaders.class, String.class));

        final List<String> excludes = ImmutableList.of(HttpHeaders.X_CONTENT_SECURITY_POLICY,
                                                       HttpHeaders.X_CONTENT_SECURITY_POLICY_REPORT_ONLY,
                                                       HttpHeaders.X_WEBKIT_CSP,
                                                       HttpHeaders.X_WEBKIT_CSP_REPORT_ONLY,
                                                       HttpHeaders.SEC_CH_PREFERS_COLOR_SCHEME)
                                                   .stream()
                                                   .map(name -> name.toUpperCase().replace('-', '_'))
                                                   .collect(toImmutableList());
        final List<String> filtered = guavaHttpHeaders.stream()
                                                      .filter(name -> !excludes.contains(name))
                                                      .collect(toImmutableList());
        assertThat(headerNames).containsAll(filtered);
    }

    private static ImmutableList<String> names(Stream<Field> constantFields) {
        return constantFields.map(Field::getName)
                             .collect(toImmutableList());
    }

    @Test
    void testOfAsciiString() {
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
    void testOfCharSequence() {
        // Should produce a lower-cased AsciiString.
        assertThat((Object) HttpHeaderNames.of("Foo")).isEqualTo(AsciiString.of("foo"));

        // Should reuse known header name instances.
        assertThat((Object) HttpHeaderNames.of("date")).isSameAs(HttpHeaderNames.DATE);
    }

    @Test
    void pseudoHeaderNameValidation() {
        // Known pseudo header names should pass validation.
        assertThat((Object) HttpHeaderNames.of(":method")).isSameAs(HttpHeaderNames.METHOD);
        assertThat((Object) HttpHeaderNames.of(":scheme")).isSameAs(HttpHeaderNames.SCHEME);
        assertThat((Object) HttpHeaderNames.of(":authority")).isSameAs(HttpHeaderNames.AUTHORITY);
        assertThat((Object) HttpHeaderNames.of(":path")).isSameAs(HttpHeaderNames.PATH);
        assertThat((Object) HttpHeaderNames.of(":status")).isSameAs(HttpHeaderNames.STATUS);

        // However, any other headers that start with `:` should fail.
        assertThatThrownBy(() -> HttpHeaderNames.of(":foo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed header name: :foo");
    }

    @Test
    void headerNameValidation() {
        assertThatThrownBy(() -> HttpHeaderNames.of(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed header name: <EMPTY>");
        assertThatThrownBy(() -> HttpHeaderNames.of("\u0000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed header name: <NUL>");
        assertThatThrownBy(() -> HttpHeaderNames.of("\t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed header name: <TAB>");
        assertThatThrownBy(() -> HttpHeaderNames.of("\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed header name: <LF>");
        assertThatThrownBy(() -> HttpHeaderNames.of("\u000B"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed header name: <VT>");
        assertThatThrownBy(() -> HttpHeaderNames.of("\f"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed header name: <FF>");
        assertThatThrownBy(() -> HttpHeaderNames.of("\r"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed header name: <CR>");
        assertThatThrownBy(() -> HttpHeaderNames.of(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed header name: <SP>");
        assertThatThrownBy(() -> HttpHeaderNames.of(","))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed header name: ,");
        assertThatThrownBy(() -> HttpHeaderNames.of(":"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed header name: :");
        assertThatThrownBy(() -> HttpHeaderNames.of(";"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed header name: ;");
        assertThatThrownBy(() -> HttpHeaderNames.of("="))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed header name: =");
    }
}
