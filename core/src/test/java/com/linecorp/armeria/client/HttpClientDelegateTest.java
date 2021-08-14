/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.client;

import static com.linecorp.armeria.client.HttpClientDelegate.extractHostFromAuthority;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;

public class HttpClientDelegateTest {
    @Test
    public void testExtractHost() {
        // additionalRequestHeaders has the highest precedence.
        assertThat(extractHostFromAuthority(context(HttpHeaders.of(HttpHeaderNames.AUTHORITY, "foo")),
                                            HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/",
                                                                             HttpHeaderNames.AUTHORITY,
                                                                             "bar:8080")))).isEqualTo("foo");

        // Request header
        assertThat(extractHostFromAuthority(context(HttpHeaders.of()),
                                            HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/",
                                                                             HttpHeaderNames.AUTHORITY,
                                                                             "bar:8080")))).isEqualTo("bar");

        // Endpoint.host() has the lowest precedence.
        assertThat(extractHostFromAuthority(context(HttpHeaders.of()),
                                            HttpRequest.of(HttpMethod.GET, "/"))).isEqualTo("baz");

        // IPv6 address authority
        assertThat(extractHostFromAuthority(context(HttpHeaders.of(HttpHeaderNames.AUTHORITY, "[::1]:8443")),
                                            HttpRequest.of(HttpMethod.GET, "/"))).isEqualTo("::1");

        // An invalid authority should be ignored.
        assertThat(extractHostFromAuthority(context(HttpHeaders.of(HttpHeaderNames.AUTHORITY, "[::1")),
                                            HttpRequest.of(HttpMethod.GET, "/"))).isNull();

        assertThat(extractHostFromAuthority(context(HttpHeaders.of(HttpHeaderNames.AUTHORITY, ":8080")),
                                            HttpRequest.of(HttpMethod.GET, "/"))).isNull();

        // If additionalRequestHeader's authority is invalid but req.authority() is valid,
        // use the authority from 'req'.
        assertThat(extractHostFromAuthority(
                context(HttpHeaders.of(HttpHeaderNames.AUTHORITY, "[::1")),
                HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/",
                                                 HttpHeaderNames.AUTHORITY, "bar")))).isEqualTo("bar");

        assertThat(extractHostFromAuthority(
                context(HttpHeaders.of(HttpHeaderNames.AUTHORITY, ":8080")),
                HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/",
                                                 HttpHeaderNames.AUTHORITY, "bar"))))
                .isEqualTo("bar");
    }

    private static ClientRequestContext context(HttpHeaders additionalHeaders) {
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx.mutateAdditionalRequestHeaders(mutator -> mutator.add(additionalHeaders));
        return ctx;
    }
}
