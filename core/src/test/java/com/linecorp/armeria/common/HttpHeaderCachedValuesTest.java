/*
 * Copyright 2021 LINE Corporation
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

import java.util.List;
import java.util.Locale.LanguageRange;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

class HttpHeaderCachedValuesTest {

    @Test
    void method() {
        // Initialize with the shortcut method
        final RequestHeadersBuilder builder = RequestHeaders.builder(HttpMethod.GET, "/foo");
        assertThat(builder.get(HttpHeaderNames.METHOD)).isEqualTo("GET");
        assertThat(builder.method()).isEqualTo(HttpMethod.GET);

        // Mutate with the non-shortcut method
        builder.set(HttpHeaderNames.METHOD, "POST");
        assertThat(builder.get(HttpHeaderNames.METHOD)).isEqualTo("POST");
        // Make sure that the cached value is invalidated
        assertThat(builder.method()).isEqualTo(HttpMethod.POST);

        // Mutate with the shortcut method
        builder.method(HttpMethod.DELETE);
        // Make sure that the container value is updated
        assertThat(builder.get(HttpHeaderNames.METHOD)).isEqualTo("DELETE");
        assertThat(builder.method()).isEqualTo(HttpMethod.DELETE);

        final RequestHeaders headers = builder.build();
        assertThat(headers.method()).isEqualTo(HttpMethod.DELETE);

        final RequestHeaders headers2 = headers.toBuilder().set(HttpHeaderNames.METHOD, "PATCH").build();
        assertThat(headers2.method()).isEqualTo(HttpMethod.PATCH);
        // The original value is not changed.
        assertThat(headers.method()).isEqualTo(HttpMethod.DELETE);
    }

    @Test
    void status() {
        // Initialize with the shortcut method
        final ResponseHeadersBuilder builder = ResponseHeaders.builder(HttpStatus.OK);
        assertThat(builder.get(HttpHeaderNames.STATUS)).isEqualTo("200");
        assertThat(builder.status()).isEqualTo(HttpStatus.OK);

        // Mutate with the non-shortcut method
        builder.set(HttpHeaderNames.STATUS, "400");
        assertThat(builder.get(HttpHeaderNames.STATUS)).isEqualTo("400");
        // Make sure that the the cached value is invalidated
        assertThat(builder.status()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Mutate with the shortcut method
        builder.status(HttpStatus.INTERNAL_SERVER_ERROR);
        // Make sure that the the container value is updated
        assertThat(builder.get(HttpHeaderNames.STATUS)).isEqualTo("500");
        assertThat(builder.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        final ResponseHeaders headers = builder.build();
        assertThat(headers.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        final ResponseHeaders headers2 = headers.toBuilder().set(HttpHeaderNames.STATUS, "303").build();
        assertThat(headers2.status()).isEqualTo(HttpStatus.SEE_OTHER);
        // The original value is not changed.
        assertThat(headers.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void contentType() {
        // Initialize with the shortcut method
        final ResponseHeadersBuilder builder = ResponseHeaders.builder(HttpStatus.OK)
                                                              .contentType(MediaType.PLAIN_TEXT);
        assertThat(builder.get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo(MediaType.PLAIN_TEXT.toString());
        assertThat(builder.contentType()).isEqualTo(MediaType.PLAIN_TEXT);

        // Mutate with the non-shortcut method
        builder.set(HttpHeaderNames.CONTENT_TYPE, MediaType.JSON.toString());
        assertThat(builder.get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo(MediaType.JSON.toString());
        // Make sure that the the cached value is invalidated
        assertThat(builder.contentType()).isEqualTo(MediaType.JSON);

        // Mutate with the shortcut method
        builder.contentType(MediaType.PROTOBUF);
        // Make sure that the the container value is updated
        assertThat(builder.get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo(MediaType.PROTOBUF.toString());
        assertThat(builder.contentType()).isEqualTo(MediaType.PROTOBUF);

        final ResponseHeaders headers = builder.build();
        assertThat(headers.contentType()).isEqualTo(MediaType.PROTOBUF);

        final ResponseHeaders headers2 =
                headers.toBuilder().setObject(HttpHeaderNames.CONTENT_TYPE, MediaType.OCTET_STREAM).build();
        assertThat(headers2.contentType()).isEqualTo(MediaType.OCTET_STREAM);
        // The original value is not changed.
        assertThat(headers.contentType()).isEqualTo(MediaType.PROTOBUF);
    }

    @Test
    void contentDisposition() {
        // Initialize with the shortcut method
        final ContentDisposition fooContentDisposition = ContentDisposition.of("foo");
        final HttpHeadersBuilder builder = HttpHeaders.builder()
                                                      .contentDisposition(fooContentDisposition);
        assertThat(builder.get(HttpHeaderNames.CONTENT_DISPOSITION))
                .isEqualTo(fooContentDisposition.asHeaderValue());
        assertThat(builder.contentDisposition()).isSameAs(fooContentDisposition);

        // Mutate with the non-shortcut method
        final ContentDisposition fooContentDisposition2 = ContentDisposition.of("foo");
        builder.setObject(HttpHeaderNames.CONTENT_DISPOSITION, fooContentDisposition2);
        assertThat(builder.get(HttpHeaderNames.CONTENT_DISPOSITION))
                .isEqualTo(fooContentDisposition2.asHeaderValue());
        // Make sure that the the cached value is invalidated
        assertThat(builder.contentDisposition()).isEqualTo(fooContentDisposition2);
        assertThat(builder.contentDisposition()).isNotSameAs(fooContentDisposition2);

        // Remove a value with the non-shortcut method
        builder.remove(HttpHeaderNames.CONTENT_DISPOSITION);
        assertThat(builder.contentDisposition()).isNull();

        // Mutate with the shortcut method
        final ContentDisposition bazContentDisposition = ContentDisposition.of("baz");
        builder.contentDisposition(bazContentDisposition);
        // Make sure that the the container value is updated
        assertThat(builder.get(HttpHeaderNames.CONTENT_DISPOSITION))
                .isEqualTo(bazContentDisposition.asHeaderValue());
        assertThat(builder.contentDisposition()).isSameAs(bazContentDisposition);

        final HttpHeaders headers = builder.build();
        assertThat(headers.contentDisposition()).isSameAs(bazContentDisposition);

        final ContentDisposition quxContentDisposition = ContentDisposition.of("qux");
        final HttpHeaders headers2 =
                headers.toBuilder()
                       .setObject(HttpHeaderNames.CONTENT_DISPOSITION, quxContentDisposition)
                       .build();
        assertThat(headers2.contentDisposition()).isEqualTo(quxContentDisposition);
        // The original value is not changed.
        assertThat(headers.contentDisposition()).isSameAs(bazContentDisposition);
    }

    @Test
    void acceptLanguages() {
        // Initialize with the shortcut method
        final RequestHeadersBuilder builder = RequestHeaders.builder(HttpMethod.GET, "/foo");
        final ImmutableList<LanguageRange> languages =
                ImmutableList.of(new LanguageRange("zh-TW", 0.8),
                                 new LanguageRange("de-us", 0.5));
        builder.acceptLanguages(languages);
        assertThat(builder.acceptLanguages()).isEqualTo(languages);

        // Mutate with the non-shortcut method
        builder.set(HttpHeaderNames.ACCEPT_LANGUAGE, "zh-tw;q=1.0, de-us;q=0.5");
        assertThat(builder.get(HttpHeaderNames.ACCEPT_LANGUAGE)).isEqualTo("zh-tw;q=1.0, de-us;q=0.5");
        // Make sure that the cached value is invalidated
        assertThat(builder.acceptLanguages()).containsExactly(new LanguageRange("zh-TW", 1.0),
                                                              new LanguageRange("de-us", 0.5));

        // Mutate with the shortcut method
        final LanguageRange englishUS = new LanguageRange("en-US", 0.9);
        builder.acceptLanguages(englishUS);
        // Make sure that the container value is updated
        assertThat(builder.get(HttpHeaderNames.ACCEPT_LANGUAGE)).isEqualTo("en-us;q=0.9");
        assertThat(builder.acceptLanguages()).hasSize(1);
        assertThat(builder.acceptLanguages().get(0)).isSameAs(englishUS);

        final RequestHeaders headers = builder.build();
        assertThat(headers.acceptLanguages().get(0)).isSameAs(englishUS);
    }

    @Test
    void cookies() {
        // Initialize with the shortcut method
        final RequestHeadersBuilder builder = RequestHeaders.builder(HttpMethod.GET, "/foo");
        final Cookie foo = Cookie.of("foo", "1");
        builder.cookies(foo);
        assertThat(builder.cookies()).hasSize(1);
        assertThat(builder.cookies().toArray()[0]).isSameAs(foo);

        // Mutate with the non-shortcut method
        final Cookie bar = Cookie.of("bar", "2");
        final Cookie baz = Cookie.of("baz", "3");
        final List<String> cookies2 = ImmutableList.of(bar.toCookieHeader(),
                                                       baz.toCookieHeader());
        builder.add(HttpHeaderNames.COOKIE, cookies2);
        // Make sure that the cached value is invalidated
        assertThat(builder.cookies()).hasSize(3);
        final List<Cookie> cookies3 = ImmutableList.copyOf(builder.cookies());
        assertThat(cookies3.get(0)).isEqualTo(foo);
        assertThat(cookies3.get(0)).isNotSameAs(foo);
        assertThat(cookies3.get(1)).isEqualTo(bar);
        assertThat(cookies3.get(2)).isEqualTo(baz);

        assertThat(builder.getAll(HttpHeaderNames.COOKIE))
                .containsExactly(foo.toCookieHeader(), bar.toSetCookieHeader(), baz.toCookieHeader());

        // Mutate with the shortcut method
        final Cookie qux = Cookie.of("qux", "4");
        builder.cookies(qux);
        // Make sure that the container value is updated
        assertThat(builder.get(HttpHeaderNames.COOKIE))
                .isEqualTo(Cookie.toCookieHeader(foo, bar, baz, qux));
        final Cookies cookies4 = builder.cookies();
        assertThat(ImmutableList.copyOf(cookies4).get(3)).isSameAs(qux);

        final RequestHeaders headers = builder.build();
        assertThat(headers.cookies()).isSameAs(cookies4);
        assertThat(headers.get(HttpHeaderNames.COOKIE)).isEqualTo(Cookie.toCookieHeader(cookies4));
    }

    @Test
    void setCookies() {
        // Initialize with the shortcut method
        final ResponseHeadersBuilder builder = ResponseHeaders.builder(HttpStatus.OK);
        final Cookie foo = Cookie.of("foo", "1");
        builder.cookies(foo);
        assertThat(builder.cookies()).hasSize(1);
        assertThat(builder.cookies().toArray()[0]).isSameAs(foo);

        // Mutate with the non-shortcut method
        final Cookie bar = Cookie.of("bar", "2");
        final Cookie baz = Cookie.of("baz", "3");
        final List<String> cookies2 = ImmutableList.of(bar.toSetCookieHeader(),
                                                       baz.toSetCookieHeader());
        builder.add(HttpHeaderNames.SET_COOKIE, cookies2);
        // Make sure that the cached value is invalidated
        assertThat(builder.cookies()).hasSize(3);
        final List<Cookie> cookies3 = ImmutableList.copyOf(builder.cookies());
        assertThat(cookies3.get(0)).isEqualTo(foo);
        assertThat(cookies3.get(0)).isNotSameAs(foo);
        assertThat(cookies3.get(1)).isEqualTo(bar);
        assertThat(cookies3.get(2)).isEqualTo(baz);

        assertThat(builder.getAll(HttpHeaderNames.SET_COOKIE))
                .containsExactly(foo.toSetCookieHeader(), bar.toSetCookieHeader(), baz.toSetCookieHeader());

        // Mutate with the shortcut method
        final Cookie qux = Cookie.of("qux", "4");
        builder.cookies(qux);
        // Make sure that the container value is updated
        assertThat(builder.getAll(HttpHeaderNames.SET_COOKIE))
                .containsExactly(foo.toSetCookieHeader(), bar.toSetCookieHeader(),
                                 baz.toSetCookieHeader(), qux.toSetCookieHeader());
        final Cookies cookies4 = builder.cookies();
        assertThat(ImmutableList.copyOf(cookies4).get(3)).isSameAs(qux);

        final ResponseHeaders headers = builder.build();
        assertThat(headers.cookies()).isSameAs(cookies4);
        assertThat(headers.getAll(HttpHeaderNames.SET_COOKIE)).isEqualTo(Cookie.toSetCookieHeaders(cookies4));
    }
}
