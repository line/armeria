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

import com.linecorp.armeria.client.encoding.DecodingClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.client.logging.LoggingRpcClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestId;

class ClientOptionsBuilderTest {
    @Test
    void testBaseOptions() {
        final ClientOptionsBuilder b =
                ClientOptions.of(ClientOptions.MAX_RESPONSE_LENGTH.newValue(42L)).toBuilder();
        assertThat(b.build().maxResponseLength()).isEqualTo(42);
    }

    @Test
    void testOptions() {
        final ClientOptionsBuilder b = ClientOptions.builder();
        b.options(ClientOptions.of(ClientOptions.RESPONSE_TIMEOUT_MILLIS.newValue(42L)));
        assertThat(b.build().responseTimeoutMillis()).isEqualTo(42);

        b.options(ClientOptions.WRITE_TIMEOUT_MILLIS.newValue(84L));
        assertThat(b.build().responseTimeoutMillis()).isEqualTo(42);
        assertThat(b.build().writeTimeoutMillis()).isEqualTo(84);
    }

    @Test
    void testOption() {
        final ClientOptionsBuilder b = ClientOptions.builder();
        b.option(ClientOptions.MAX_RESPONSE_LENGTH, 123L);
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
    void testEmptyClientDecoration() {
        assertThat(ClientDecoration.of().isEmpty()).isTrue();
        assertThat(ClientDecoration.builder().build().isEmpty()).isTrue();
        assertThat(ClientDecoration.of(LoggingClient.newDecorator()).isEmpty()).isFalse();
    }

    @Test
    void testDecorators() {
        final ClientOptionsBuilder b = ClientOptions.builder();
        final Function<? super HttpClient, ? extends HttpClient> decorator =
                LoggingClient.newDecorator();

        b.option(ClientOptions.DECORATION.newValue(ClientDecoration.builder()
                                                                   .add(decorator)
                                                                   .build()));

        assertThat(b.build().decoration().decorators()).containsExactly(decorator);

        // Add another decorator to ensure that the builder does not replace the previous one.
        final Function<? super HttpClient, ? extends HttpClient> decorator2 =
                DecodingClient.newDecorator();
        b.option(ClientOptions.DECORATION.newValue(ClientDecoration.builder()
                                                                   .add(decorator2)
                                                                   .build()));
        assertThat(b.build().decoration().decorators()).containsSequence(decorator, decorator2);

        // Add an RPC decorator.
        final Function<? super RpcClient, ? extends RpcClient> rpcDecorator =
                LoggingRpcClient.newDecorator();
        b.option(ClientOptions.DECORATION.newValue(ClientDecoration.builder()
                                                                   .addRpc(rpcDecorator)
                                                                   .build()));

        assertThat(b.build().decoration().decorators()).containsSequence(decorator, decorator2);
        assertThat(b.build().decoration().rpcDecorators()).containsExactly(rpcDecorator);

        final Function<? super HttpClient, ? extends HttpClient> decorator3 =
                DecodingClient.newDecorator();
        // Insert decorator at first.
        final ClientDecoration decoration = b.build().decoration();
        b.clearDecorators();
        assertThat(b.build().decoration().decorators()).isEmpty();
        assertThat(b.build().decoration().rpcDecorators()).isEmpty();

        b.decorator(decorator3);
        decoration.decorators().forEach(b::decorator);
        assertThat(b.build().decoration().decorators()).containsSequence(decorator3, decorator, decorator2);
        assertThat(b.build().decoration().rpcDecorators()).isEmpty();
    }

    @Test
    void testHeaders() {
        final ClientOptionsBuilder b = ClientOptions.builder();

        b.option(ClientOptions.HEADERS.newValue(HttpHeaders.of(HttpHeaderNames.ACCEPT, "*/*")));

        // Add another header to ensure that the builder does not replace the previous one.
        b.option(ClientOptions.HEADERS.newValue(HttpHeaders.of(HttpHeaderNames.USER_AGENT, "foo")));

        final HttpHeaders mergedHeaders = b.build().headers();
        assertThat(mergedHeaders.get(HttpHeaderNames.ACCEPT)).isEqualTo("*/*");
        assertThat(mergedHeaders.get(HttpHeaderNames.USER_AGENT)).isEqualTo("foo");
    }

    @Test
    void testSetHeaders() {
        final ClientOptionsBuilder b = ClientOptions.builder();
        b.setHeaders(HttpHeaders.of(HttpHeaderNames.AUTHORIZATION, "Basic QWxhZGRpbjpPcGVuU2VzYW1l"));

        assertThat(b.build().headers().get(HttpHeaderNames.AUTHORIZATION))
                .isEqualTo("Basic QWxhZGRpbjpPcGVuU2VzYW1l");
    }

    @Test
    void testSetHeader() {
        final ClientOptionsBuilder b = ClientOptions.builder();
        // Ensure setHttpHeader replaces instead of adding.
        b.setHeader(HttpHeaderNames.AUTHORIZATION, "Basic QWxhZGRpbjpPcGVuU2VzYW1l");
        b.setHeader(HttpHeaderNames.AUTHORIZATION, "Lost token");

        assertThat(b.build().headers().get(HttpHeaderNames.AUTHORIZATION)).isEqualTo("Lost token");
    }

    @Test
    void testAddHeaders() {
        final ClientOptionsBuilder b = ClientOptions.builder();
        b.addHeaders(HttpHeaders.of(HttpHeaderNames.AUTHORIZATION, "Basic QWxhZGRpbjpPcGVuU2VzYW1l"));

        assertThat(b.build().headers().get(HttpHeaderNames.AUTHORIZATION))
                .isEqualTo("Basic QWxhZGRpbjpPcGVuU2VzYW1l");
    }

    @Test
    void testAddHeader() {
        final ClientOptionsBuilder b = ClientOptions.builder();
        // Ensure addHttpHeader does not replace.
        b.addHeader(HttpHeaderNames.AUTHORIZATION, "Basic QWxhZGRpbjpPcGVuU2VzYW1l");
        b.addHeader(HttpHeaderNames.AUTHORIZATION, "Lost token");

        assertThat(b.build().headers().getAll(HttpHeaderNames.AUTHORIZATION)).containsExactly(
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

        assertThat(outer.as(inner.getClass())).isSameAs(inner);
        assertThat(outer.as(outer.getClass())).isSameAs(outer);
        assertThat(outer.as(LoggingClient.class)).isNull();
    }

    private static final class FooClient implements HttpClient {
        FooClient() { }

        @Override
        public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
            // Will never reach here.
            throw new Error();
        }
    }

    private static final class FooDecorator extends SimpleDecoratingHttpClient {
        FooDecorator(HttpClient delegate) {
            super(delegate);
        }

        @Override
        public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
            // Will never reach here.
            throw new Error();
        }
    }
}
