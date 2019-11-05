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
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientDecoration.Entry;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;

class ClientOptionsBuilderTest {
    @Test
    void testBaseOptions() {
        final ClientOptionsBuilder b =
                ClientOptions.of(ClientOption.MAX_RESPONSE_LENGTH.newValue(42L)).toBuilder();
        assertThat(b.build().maxResponseLength()).isEqualTo(42);
    }

    @Test
    void testOptions() {
        final ClientOptionsBuilder b = ClientOptions.builder();
        b.options(ClientOptions.of(ClientOption.RESPONSE_TIMEOUT_MILLIS.newValue(42L)));
        assertThat(b.build().responseTimeoutMillis()).isEqualTo(42);

        b.options(ClientOption.WRITE_TIMEOUT_MILLIS.newValue(84L));
        assertThat(b.build().responseTimeoutMillis()).isEqualTo(42);
        assertThat(b.build().writeTimeoutMillis()).isEqualTo(84);
    }

    @Test
    void testOption() {
        final ClientOptionsBuilder b = ClientOptions.builder();
        b.option(ClientOption.MAX_RESPONSE_LENGTH, 123L);
        assertThat(b.build().maxResponseLength()).isEqualTo(123);
    }

    @Test
    void testIdGenerator() {
        final Supplier<RequestId> expected = () -> null;
        final ClientOptionsBuilder b = ClientOptions.builder();
        b.requestIdGenerator(expected);
        final ClientOptions options = b.build();
        assertThat(options.requestIdGenerator()).isSameAs(expected);
    }

    @Test
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void testDecorators() {
        final ClientOptionsBuilder b = ClientOptions.builder();
        final Function decorator = LoggingClient.newDecorator();

        b.option(ClientOption.DECORATION.newValue(ClientDecoration.builder()
                                                                  .add(decorator)
                                                                  .build()));

        assertThat(b.build().decoration().entries()).containsExactly(
                new Entry<>(HttpRequest.class, HttpResponse.class, decorator));

        // Add another decorator to ensure that the builder does not replace the previous one.
        b.option(ClientOption.DECORATION.newValue(ClientDecoration.builder()
                                                                  .addRpc(decorator)
                                                                  .build()));

        assertThat(b.build().decoration().entries()).containsExactly(
                new Entry<>(HttpRequest.class, HttpResponse.class, decorator),
                new Entry<>(RpcRequest.class, RpcResponse.class, decorator));
    }

    @Test
    void testHttpHeaders() {
        final ClientOptionsBuilder b = ClientOptions.builder();

        b.option(ClientOption.HTTP_HEADERS.newValue(HttpHeaders.of(HttpHeaderNames.ACCEPT, "*/*")));

        // Add another header to ensure that the builder does not replace the previous one.
        b.option(ClientOption.HTTP_HEADERS.newValue(HttpHeaders.of(HttpHeaderNames.USER_AGENT, "foo")));

        final HttpHeaders mergedHeaders = b.build().httpHeaders();
        assertThat(mergedHeaders.get(HttpHeaderNames.ACCEPT)).isEqualTo("*/*");
        assertThat(mergedHeaders.get(HttpHeaderNames.USER_AGENT)).isEqualTo("foo");
    }

    @Test
    void testSetHttpHeaders() {
        final ClientOptionsBuilder b = ClientOptions.builder();
        b.setHttpHeaders(HttpHeaders.of(HttpHeaderNames.AUTHORIZATION, "Basic QWxhZGRpbjpPcGVuU2VzYW1l"));

        assertThat(b.build().httpHeaders().get(HttpHeaderNames.AUTHORIZATION))
                .isEqualTo("Basic QWxhZGRpbjpPcGVuU2VzYW1l");
    }

    @Test
    void testSetHttpHeader() {
        final ClientOptionsBuilder b = ClientOptions.builder();
        // Ensure setHttpHeader replaces instead of adding.
        b.setHttpHeader(HttpHeaderNames.AUTHORIZATION, "Basic QWxhZGRpbjpPcGVuU2VzYW1l");
        b.setHttpHeader(HttpHeaderNames.AUTHORIZATION, "Lost token");

        assertThat(b.build().httpHeaders().get(HttpHeaderNames.AUTHORIZATION)).isEqualTo("Lost token");
    }

    @Test
    void testAddHttpHeaders() {
        final ClientOptionsBuilder b = ClientOptions.builder();
        b.addHttpHeaders(HttpHeaders.of(HttpHeaderNames.AUTHORIZATION, "Basic QWxhZGRpbjpPcGVuU2VzYW1l"));

        assertThat(b.build().httpHeaders().get(HttpHeaderNames.AUTHORIZATION))
                .isEqualTo("Basic QWxhZGRpbjpPcGVuU2VzYW1l");
    }

    @Test
    void testAddHttpHeader() {
        final ClientOptionsBuilder b = ClientOptions.builder();
        // Ensure addHttpHeader does not replace.
        b.addHttpHeader(HttpHeaderNames.AUTHORIZATION, "Basic QWxhZGRpbjpPcGVuU2VzYW1l");
        b.addHttpHeader(HttpHeaderNames.AUTHORIZATION, "Lost token");

        assertThat(b.build().httpHeaders().getAll(HttpHeaderNames.AUTHORIZATION)).containsExactly(
                "Basic QWxhZGRpbjpPcGVuU2VzYW1l", "Lost token");
    }

    @Test
    void testShortcutMethods() {
        final ClientOptionsBuilder b = ClientOptions.builder();
        b.writeTimeout(Duration.ofSeconds(1));
        b.responseTimeout(Duration.ofSeconds(2));
        b.maxResponseLength(3000);

        final ClientOptions opts = b.build();
        assertThat(opts.writeTimeoutMillis()).isEqualTo(1000);
        assertThat(opts.responseTimeoutMillis()).isEqualTo(2000);
        assertThat(opts.maxResponseLength()).isEqualTo(3000);
    }

    @Test
    void testDecoratorDowncast() {
        final FooClient inner = new FooClient();
        final FooDecorator outer = new FooDecorator(inner);

        assertThat(outer.as(inner.getClass())).isPresent();
        assertThat(outer.as(outer.getClass())).isPresent();

        if (outer.as(inner.getClass()).isPresent()) {
            assertThat(outer.as(inner.getClass()).get() == inner);
        }

        if (outer.as(outer.getClass()).isPresent()) {
            assertThat(outer.as(outer.getClass()).get() == outer);
        }

        assertThat(outer.as(LoggingClient.class).isPresent()).isFalse();
    }

    private static final class FooClient implements Client<HttpRequest, HttpResponse> {
        FooClient() { }

        @Override
        public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
            // Will never reach here.
            throw new Error();
        }
    }

    private static final class FooDecorator extends SimpleDecoratingClient<HttpRequest, HttpResponse> {
        FooDecorator(Client<HttpRequest, HttpResponse> delegate) {
            super(delegate);
        }

        @Override
        public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
            // Will never reach here.
            throw new Error();
        }
    }
}
