/*
 * Copyright 2016 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.function.Function;

import org.junit.Test;

import com.linecorp.armeria.client.ClientDecoration.Entry;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;

public class ClientOptionsBuilderTest {
    @Test
    public void testBaseOptions() {
        final ClientOptionsBuilder b = new ClientOptionsBuilder(
                ClientOptions.of(ClientOption.DEFAULT_MAX_RESPONSE_LENGTH.newValue(42L)));
        assertThat(b.build().defaultMaxResponseLength()).isEqualTo(42);
    }

    @Test
    public void testOptions() {
        final ClientOptionsBuilder b = new ClientOptionsBuilder();
        b.options(ClientOptions.of(ClientOption.DEFAULT_RESPONSE_TIMEOUT_MILLIS.newValue(42L)));
        assertThat(b.build().defaultResponseTimeoutMillis()).isEqualTo(42);

        b.options(ClientOption.DEFAULT_WRITE_TIMEOUT_MILLIS.newValue(84L));
        assertThat(b.build().defaultResponseTimeoutMillis()).isEqualTo(42);
        assertThat(b.build().defaultWriteTimeoutMillis()).isEqualTo(84);
    }

    @Test
    public void testOption() {
        final ClientOptionsBuilder b = new ClientOptionsBuilder();
        b.option(ClientOption.DEFAULT_MAX_RESPONSE_LENGTH, 123L);
        assertThat(b.build().defaultMaxResponseLength()).isEqualTo(123);
    }

    @Test
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void testDecorators() {
        final ClientOptionsBuilder b = new ClientOptionsBuilder();
        final Function decorator = LoggingClient.newDecorator();

        b.option(ClientOption.DECORATION.newValue(
                new ClientDecorationBuilder()
                        .add(HttpRequest.class, HttpResponse.class, decorator)
                        .build()));

        assertThat(b.build().decoration().entries()).containsExactly(
                new Entry<>(HttpRequest.class, HttpResponse.class, decorator));

        // Add another decorator to ensure that the builder does not replace the previous one.
        b.option(ClientOption.DECORATION.newValue(
                new ClientDecorationBuilder()
                        .add(RpcRequest.class, RpcResponse.class, decorator)
                        .build()));
        assertThat(b.build().decoration().entries()).containsExactly(
                new Entry<>(HttpRequest.class, HttpResponse.class, decorator),
                new Entry<>(RpcRequest.class, RpcResponse.class, decorator));
    }

    @Test
    public void testHttpHeaders() {
        final ClientOptionsBuilder b = new ClientOptionsBuilder();

        b.option(ClientOption.HTTP_HEADERS.newValue(HttpHeaders.of(HttpHeaderNames.ACCEPT, "*/*")));
        assertThat(b.build().httpHeaders().get(HttpHeaderNames.ACCEPT)).isEqualTo("*/*");

        // Add another header to ensure that the builder does not replace the previous one.
        b.option(ClientOption.HTTP_HEADERS.newValue(HttpHeaders.of(HttpHeaderNames.USER_AGENT, "foo")));

        final HttpHeaders mergedHeaders = b.build().httpHeaders();
        assertThat(mergedHeaders.get(HttpHeaderNames.ACCEPT)).isEqualTo("*/*");
        assertThat(mergedHeaders.get(HttpHeaderNames.USER_AGENT)).isEqualTo("foo");
    }

    @Test
    public void testSetHttpHeader() {
        final ClientOptionsBuilder b = new ClientOptionsBuilder();
        b.setHttpHeader(HttpHeaderNames.AUTHORIZATION, "Basic QWxhZGRpbjpPcGVuU2VzYW1l");

        assertThat(b.build().httpHeaders().get(HttpHeaderNames.AUTHORIZATION))
                .isEqualTo("Basic QWxhZGRpbjpPcGVuU2VzYW1l");

        // Ensure setHttpHeader replaces instead of adding.
        b.setHttpHeader(HttpHeaderNames.AUTHORIZATION, "Lost token");
        assertThat(b.build().httpHeaders().get(HttpHeaderNames.AUTHORIZATION)).isEqualTo("Lost token");
    }

    @Test
    public void testAddHttpHeader() {
        final ClientOptionsBuilder b = new ClientOptionsBuilder();
        b.addHttpHeaders(HttpHeaders.of(HttpHeaderNames.AUTHORIZATION, "Basic QWxhZGRpbjpPcGVuU2VzYW1l"));

        assertThat(b.build().httpHeaders().get(HttpHeaderNames.AUTHORIZATION))
                .isEqualTo("Basic QWxhZGRpbjpPcGVuU2VzYW1l");

        // Ensure addHttpHeader does not replace.
        b.addHttpHeader(HttpHeaderNames.AUTHORIZATION, "Lost token");
        assertThat(b.build().httpHeaders().getAll(HttpHeaderNames.AUTHORIZATION)).containsExactly(
                "Basic QWxhZGRpbjpPcGVuU2VzYW1l", "Lost token");
    }

    @Test
    public void testShortcutMethods() {
        final ClientOptionsBuilder b = new ClientOptionsBuilder();
        b.defaultWriteTimeout(Duration.ofSeconds(1));
        b.defaultResponseTimeout(Duration.ofSeconds(2));
        b.defaultMaxResponseLength(3000);

        final ClientOptions opts = b.build();
        assertThat(opts.defaultWriteTimeoutMillis()).isEqualTo(1000);
        assertThat(opts.defaultResponseTimeoutMillis()).isEqualTo(2000);
        assertThat(opts.defaultMaxResponseLength()).isEqualTo(3000);
    }
}
