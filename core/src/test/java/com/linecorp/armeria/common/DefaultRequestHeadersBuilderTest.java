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
}
