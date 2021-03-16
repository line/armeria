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

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import com.google.common.collect.Maps;

class DefaultResponseHeadersBuilderTest {
    @Test
    void mutationAfterBuild() {
        final ResponseHeaders headers = ResponseHeaders.of(200);
        final DefaultResponseHeadersBuilder builder = (DefaultResponseHeadersBuilder) headers.toBuilder();

        // Initial state
        assertThat(builder.parent()).isSameAs(headers);
        assertThat(builder.delegate()).isNull();

        // 1st mutation
        builder.add("c", "d");
        assertThat(builder.parent()).isSameAs(headers);
        assertThat(builder.delegate()).isNotNull().isNotSameAs(builder.parent());

        // 1st promotion
        HttpHeadersBase oldDelegate = builder.delegate();
        final HttpHeaders headers2 = builder.build();
        assertThat(headers2).isNotSameAs(headers);
        assertThat(((HttpHeadersBase) headers2).entries).isNotSameAs(((HttpHeadersBase) headers).entries);
        assertThat(builder.parent()).isSameAs(oldDelegate);
        assertThat(builder.delegate()).isNull();

        // 2nd mutation
        builder.add("e", "f");
        assertThat(builder.parent()).isSameAs(oldDelegate);
        assertThat(builder.delegate()).isNotNull().isNotSameAs(builder.parent());

        // 2nd promotion
        oldDelegate = builder.delegate();
        final HttpHeaders headers3 = builder.build();
        assertThat(headers3).isNotSameAs(headers);
        assertThat(headers3).isNotSameAs(headers2);
        assertThat(((HttpHeadersBase) headers3).entries).isNotSameAs(((HttpHeadersBase) headers).entries);
        assertThat(((HttpHeadersBase) headers3).entries).isNotSameAs(((HttpHeadersBase) headers2).entries);
        assertThat(builder.parent()).isSameAs(oldDelegate);
        assertThat(builder.delegate()).isNull();

        // 3rd mutation, to make sure it doesn't affect the previously built headers.
        builder.clear();

        // Check the content.
        assertThat(headers).containsExactly(Maps.immutableEntry(HttpHeaderNames.STATUS, "200"));
        assertThat(headers2).containsExactly(Maps.immutableEntry(HttpHeaderNames.STATUS, "200"),
                                             Maps.immutableEntry(HttpHeaderNames.of("c"), "d"));
        assertThat(headers3).containsExactly(Maps.immutableEntry(HttpHeaderNames.STATUS, "200"),
                                             Maps.immutableEntry(HttpHeaderNames.of("c"), "d"),
                                             Maps.immutableEntry(HttpHeaderNames.of("e"), "f"));
    }

    @Test
    void noMutationNoCopy() {
        final ResponseHeaders headers = ResponseHeaders.of(200);
        final DefaultResponseHeadersBuilder builder = (DefaultResponseHeadersBuilder) headers.toBuilder();
        assertThat(builder.build()).isSameAs(headers);
        assertThat(builder.delegate()).isNull();
    }

    @Test
    void buildTwice() {
        final ResponseHeadersBuilder builder = ResponseHeaders.builder(200).add("foo", "bar");
        assertThat(builder.build()).isEqualTo(ResponseHeaders.of(HttpStatus.OK, "foo", "bar"));
        assertThat(builder.build()).isEqualTo(ResponseHeaders.of(HttpStatus.OK, "foo", "bar"));
    }

    @Test
    void validation() {
        // When delegate is null.
        assertThatThrownBy(() -> ResponseHeaders.builder().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(":status");
        // When delegate is non-null.
        assertThatThrownBy(() -> ResponseHeaders.builder().add("a", "b").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(":status");
    }

    @Test
    void testSetCookieBuilder() {
        final Cookie cookie = Cookie.of("cookie", "value");
        final ResponseHeaders headers = ResponseHeaders
                .builder(HttpStatus.OK)
                .setCookie(cookie)
                .build();
        assertThat(headers.get(HttpHeaderNames.SET_COOKIE)).isEqualTo(cookie.toCookieHeader());
    }

    @Test
    void testSetCookieBuilderWithIterable() {
        final Cookies cookies = Cookies.of(Cookie.of("cookie1", "value1"),
                                           Cookie.of("cookie2", "value2"));
        final ResponseHeaders headers = ResponseHeaders
                .builder(HttpStatus.OK)
                .setCookie(cookies)
                .build();
        assertThat(headers.getAll(HttpHeaderNames.SET_COOKIE)).contains("cookie1=value1", "cookie2=value2");
    }

    @Test
    void testSetCookieBuilderWithMultipleCookie() {
        final Cookie cookie1 = Cookie.of("cookie1", "value1");
        final Cookie cookie2 = Cookie.of("cookie2", "value2");
        final ResponseHeaders headers = ResponseHeaders
                .builder(HttpStatus.OK)
                .setCookie(cookie1, cookie2)
                .build();
        assertThat(headers.getAll(HttpHeaderNames.SET_COOKIE)).contains("cookie1=value1", "cookie2=value2");
    }

    /**
     * Makes sure {@link ResponseHeadersBuilder} overrides all {@link HttpHeadersBuilder} methods
     * with the correct return type.
     */
    @Test
    void methodChaining() throws Exception {
        for (Method m : ResponseHeadersBuilder.class.getMethods()) {
            if (m.getReturnType() == HttpHeadersBuilder.class) {
                final Method overriddenMethod =
                        ResponseHeadersBuilder.class.getDeclaredMethod(m.getName(), m.getParameterTypes());
                assertThat(overriddenMethod.getReturnType()).isSameAs(ResponseHeadersBuilder.class);
            }
        }
    }
}
