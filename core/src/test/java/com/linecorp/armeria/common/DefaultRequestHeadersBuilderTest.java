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
import java.util.List;
import java.util.Locale;
import java.util.Locale.LanguageRange;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

import com.linecorp.armeria.client.Endpoint;

class DefaultRequestHeadersBuilderTest {

    @Test
    void mutationAfterBuild() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/");
        final DefaultRequestHeadersBuilder builder = (DefaultRequestHeadersBuilder) headers.toBuilder();

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

        // Ensure the 3 headers are independent from each other.
        assertThat(headers).isNotSameAs(headers2);
        assertThat(headers).isNotSameAs(headers3);
        assertThat(headers).containsExactly(Maps.immutableEntry(HttpHeaderNames.METHOD, "GET"),
                                            Maps.immutableEntry(HttpHeaderNames.PATH, "/"));
        assertThat(headers2).containsExactly(Maps.immutableEntry(HttpHeaderNames.METHOD, "GET"),
                                             Maps.immutableEntry(HttpHeaderNames.PATH, "/"),
                                             Maps.immutableEntry(HttpHeaderNames.of("c"), "d"));
        assertThat(headers3).containsExactly(Maps.immutableEntry(HttpHeaderNames.METHOD, "GET"),
                                             Maps.immutableEntry(HttpHeaderNames.PATH, "/"),
                                             Maps.immutableEntry(HttpHeaderNames.of("c"), "d"),
                                             Maps.immutableEntry(HttpHeaderNames.of("e"), "f"));
    }

    @Test
    void noMutationNoCopy() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/");
        final DefaultRequestHeadersBuilder builder = (DefaultRequestHeadersBuilder) headers.toBuilder();
        assertThat(builder.build()).isSameAs(headers);
        assertThat(builder.delegate()).isNull();
    }

    @Test
    void buildTwice() {
        final RequestHeadersBuilder builder = RequestHeaders.builder(HttpMethod.GET, "/").add("foo", "bar");
        assertThat(builder.build()).isEqualTo(RequestHeaders.of(HttpMethod.GET, "/", "foo", "bar"));
        assertThat(builder.build()).isEqualTo(RequestHeaders.of(HttpMethod.GET, "/", "foo", "bar"));
    }

    @Test
    void validation() {
        assertThatThrownBy(() -> RequestHeaders.builder().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(":method")
                .hasMessageContaining(":path");
        assertThatThrownBy(() -> RequestHeaders.builder().method(HttpMethod.GET).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(":path");
        assertThatThrownBy(() -> RequestHeaders.builder().path("/").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(":method");

        // URI validation.
        assertThatThrownBy(() -> RequestHeaders.builder().uri())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(":scheme")
                .hasMessageContaining(":authority")
                .hasMessageContaining(":path");
        assertThatThrownBy(() -> RequestHeaders.builder().path("/").authority("foo.com").uri())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(":scheme");
        assertThatThrownBy(() -> RequestHeaders.builder().path("/").scheme("http").uri())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(":authority");
        assertThatThrownBy(() -> RequestHeaders.builder().authority("foo.com").scheme("http").uri())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(":path");
    }

    @Test
    void authorityFromEndpoint() {
        final RequestHeadersBuilder builder = RequestHeaders.builder();
        assertThat(builder.authority(Endpoint.of("foo", 8080)).authority()).isEqualTo("foo:8080");
    }

    @Test
    void schemeFromSessionProtocol() {
        final RequestHeadersBuilder builder = RequestHeaders.builder();
        SessionProtocol.httpValues().forEach(p -> assertThat(builder.scheme(p).scheme()).isEqualTo("http"));
        SessionProtocol.httpsValues().forEach(p -> assertThat(builder.scheme(p).scheme()).isEqualTo("https"));
        assertThatThrownBy(() -> builder.scheme(SessionProtocol.PROXY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testAcceptLanguageMultiHeader() {
        final RequestHeaders headers = RequestHeaders
                .builder()
                .path("/")
                .method(HttpMethod.GET)
                .add(HttpHeaderNames.ACCEPT_LANGUAGE,
                        "de-us ;q=0.9, *;q=0.8",
                        "en;q=0.95, en-US;q=0.98"
                )
                .build();
        final List<LanguageRange> acceptLanguages = headers.acceptLanguages();
        assertThat(acceptLanguages)
                .isEqualTo(
                        ImmutableList.of(
                                new LanguageRange("en-US", 0.98),
                                new LanguageRange("en", 0.95),
                                new LanguageRange("de-us", 0.9),
                                new LanguageRange("*", 0.8)
                        )
                );
    }

    @Test
    void testAcceptLanguageNoHeader() {
        final RequestHeaders headers = RequestHeaders
                .builder()
                .path("/")
                .method(HttpMethod.GET)
                .build();
        final List<LanguageRange> acceptLanguages = headers.acceptLanguages();
        assertThat(acceptLanguages).isNull();
    }

    @Test
    void testAcceptLanguageEmptyHeader() {
        final RequestHeaders headers = RequestHeaders
                .builder()
                .path("/")
                .method(HttpMethod.GET)
                .add(HttpHeaderNames.ACCEPT_LANGUAGE, "")
                .build();
        final List<LanguageRange> acceptLanguages = headers.acceptLanguages();
        assertThat(acceptLanguages).isNull();
    }

    @Test
    void testAcceptLanguagesOrdering() {
        final RequestHeaders headers = RequestHeaders
                .builder()
                .path("/")
                .method(HttpMethod.GET)
                .set(HttpHeaderNames.ACCEPT_LANGUAGE, "de-us ;q=0.9, *;q=0.8,en;q=0.95, en-US;q=0.98")
                .build();
        final List<Locale> supportedLocales = ImmutableList.of(
                Locale.GERMANY,
                Locale.UK
        );
        final Locale bestLocale = headers.selectLocale(supportedLocales);
        assertThat(bestLocale).isEqualTo(Locale.UK);
    }

    @Test
    void testAcceptLanguageNoMatchingLanguages() {
        final RequestHeaders headers = RequestHeaders
                .builder()
                .path("/")
                .method(HttpMethod.GET)
                .set(HttpHeaderNames.ACCEPT_LANGUAGE, "en-US;q=0.98")
                .build();

        assertThat(
                headers.selectLocale(ImmutableList.of(Locale.TRADITIONAL_CHINESE))
        ).isNull();
    }

    @Test
    void testInvalidAcceptLanguageHeader() {
        final List<LanguageRange> acceptLanguages = RequestHeaders
                .builder()
                .set(HttpHeaderNames.ACCEPT_LANGUAGE, "en-US;0.98")
                .acceptLanguages();
        assertThat(acceptLanguages).isNull();
    }

    @Test
    void testSettingOfAcceptLanguage() {
        final String acceptLanguage = RequestHeaders
                .builder()
                .acceptLanguages(
                        ImmutableList.of(
                                new LanguageRange("zh-TW", 0.8),
                                new LanguageRange("de-us", 0.5)
                        )
                )
                .get(HttpHeaderNames.ACCEPT_LANGUAGE);
        assertThat(acceptLanguage).isEqualTo("zh-tw;q=0.8, de-us;q=0.5");
    }

    @Test
    void testAcceptLanguageBuilderGetter() {
        final ImmutableList<LanguageRange> languageRanges = ImmutableList.of(
                new LanguageRange("zh-TW", 0.8),
                new LanguageRange("de-us", 0.5)
        );
        final List<LanguageRange> acceptLanguages = RequestHeaders
                .builder()
                .acceptLanguages(
                        languageRanges
                )
                .acceptLanguages();
        assertThat(acceptLanguages).isEqualTo(languageRanges);
    }

    @Test
    void testCookieBuilderTest() {
        final Cookie cookie = Cookie.of("cookie", "value");
        final RequestHeaders headers = RequestHeaders
                .builder()
                .path("/")
                .method(HttpMethod.GET)
                .cookie(cookie)
                .build();
        assertThat(headers.get(HttpHeaderNames.COOKIE)).isEqualTo(cookie.toCookieHeader());
    }

    /**
     * Makes sure {@link RequestHeadersBuilder} overrides all {@link HttpHeadersBuilder} methods
     * with the correct return type.
     */
    @Test
    void methodChaining() throws Exception {
        for (Method m : RequestHeadersBuilder.class.getMethods()) {
            if (m.getReturnType() == HttpHeadersBuilder.class) {
                final Method overriddenMethod =
                        RequestHeadersBuilder.class.getDeclaredMethod(m.getName(), m.getParameterTypes());
                assertThat(overriddenMethod.getReturnType()).isSameAs(RequestHeadersBuilder.class);
            }
        }
    }
}
