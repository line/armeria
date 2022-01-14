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
        // Make sure that the cached value is invalidated
        assertThat(builder.status()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Mutate with the shortcut method
        builder.status(HttpStatus.INTERNAL_SERVER_ERROR);
        // Make sure that the container value is updated
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
        // Make sure that the cached value is invalidated
        assertThat(builder.contentType()).isEqualTo(MediaType.JSON);

        // Mutate with the shortcut method
        builder.contentType(MediaType.PROTOBUF);
        // Make sure that the container value is updated
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
    void contentLength() {
        // Initialize with the shortcut method
        final HttpHeadersBuilder builder = HttpHeaders.builder()
                                                      .contentLength(1000);
        assertThat(builder.get(HttpHeaderNames.CONTENT_LENGTH))
                .isEqualTo("1000");
        assertThat(builder.contentLength()).isEqualTo(1000);

        // Mutate with the non-shortcut method
        builder.setLong(HttpHeaderNames.CONTENT_LENGTH, 2000);
        assertThat(builder.get(HttpHeaderNames.CONTENT_LENGTH))
                .isEqualTo("2000");
        // Make sure that the cached value is invalidated
        assertThat(builder.contentLength()).isEqualTo(2000);

        // Remove a value with the non-shortcut method
        builder.remove(HttpHeaderNames.CONTENT_LENGTH);
        assertThat(builder.contentLength()).isEqualTo(-1);

        // Mutate with the shortcut method
        builder.contentLength(3000);
        // Make sure that the container value is updated
        assertThat(builder.get(HttpHeaderNames.CONTENT_LENGTH))
                .isEqualTo("3000");
        assertThat(builder.contentLength()).isEqualTo(3000);

        final HttpHeaders headers = builder.build();
        assertThat(headers.contentLength()).isEqualTo(3000);

        final HttpHeaders headers2 =
                headers.toBuilder()
                       .setObject(HttpHeaderNames.CONTENT_LENGTH, 4000)
                       .build();
        assertThat(headers2.contentLength()).isEqualTo(4000);
        // Make sure that the original value is not changed.
        assertThat(headers.contentLength()).isEqualTo(3000);
    }

    @Test
    void cookies() {
        // Initialize with the shortcut method
        final RequestHeadersBuilder builder = RequestHeaders.builder(HttpMethod.GET, "/foo");
        final Cookie foo = Cookie.ofSecure("foo", "1");
        builder.cookies(foo);
        assertThat(builder.cookies()).hasSize(1);
        assertThat(builder.cookies().toArray()[0]).isSameAs(foo);

        // Mutate with the non-shortcut method
        final Cookie bar = Cookie.ofSecure("bar", "2");
        final Cookie baz = Cookie.ofSecure("baz", "3");
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
                .containsExactly(foo.toCookieHeader(), bar.toCookieHeader(), baz.toCookieHeader());

        // Mutate with the shortcut method
        final Cookie qux = Cookie.ofSecure("qux", "4");
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

    @Test
    void accept() {
        // Initialize with the shortcut method
        final RequestHeadersBuilder builder = RequestHeaders.builder(HttpMethod.GET, "/foo");
        final MediaType foo = MediaType.PLAIN_TEXT;
        builder.accept(foo);
        assertThat(builder.accept()).containsExactly(foo);

        // Mutate with the non-shortcut method
        final MediaType bar = MediaType.JSON;
        final MediaType baz = MediaType.PROTOBUF;
        final List<MediaType> accepts = ImmutableList.of(bar, baz);
        builder.addObject(HttpHeaderNames.ACCEPT, accepts);
        // Make sure that the cached value is invalidated
        assertThat(builder.accept()).hasSize(3);
        final List<MediaType> accepts3 = builder.accept();
        assertThat(accepts3.get(0)).isEqualTo(foo);
        assertThat(accepts3.get(1)).isEqualTo(bar);
        assertThat(accepts3.get(2)).isEqualTo(baz);

        assertThat(builder.getAll(HttpHeaderNames.ACCEPT))
                .containsExactly(foo.toString(), bar.toString(), baz.toString());

        // Mutate with the shortcut method
        final MediaType qux = MediaType.FORM_DATA;
        builder.accept(qux);
        // Make sure that the container value is updated
        assertThat(builder.getAll(HttpHeaderNames.ACCEPT))
                .containsExactly(foo.toString(), bar.toString(), baz.toString(), qux.toString());
        final List<MediaType> accepts4 = builder.accept();
        assertThat(ImmutableList.copyOf(accepts4).get(3)).isSameAs(qux);

        final RequestHeaders headers = builder.build();
        assertThat(headers.accept()).isSameAs(accepts4);
    }
}
