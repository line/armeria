/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;

class ClientDecorationTest {

    @Test
    void lambdaDecorator() {
        final BlockingWebClient client =
                WebClient.builder()
                         .decorator(delegate -> (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                         .build()
                         .blocking();

        final AggregatedHttpResponse response = client.get("http://127.0.0.1/");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void lambdaDecoratorThatDelegates() {
        final BlockingWebClient client =
                WebClient.builder()
                         // inner: returns a response directly
                         .decorator(delegate -> (ctx, req) -> HttpResponse.of("hello"))
                         // outer: lambda that delegates to inner
                         .decorator(delegate -> (ctx, req) -> delegate.execute(ctx, req))
                         .build()
                         .blocking();

        final AggregatedHttpResponse response = client.get("http://127.0.0.1/");
        assertThat(response.contentUtf8()).isEqualTo("hello");
    }

    @Test
    void mixedLambdaAndSimpleDecoratingClient() {
        final BlockingWebClient client =
                WebClient.builder()
                         // inner: lambda decorator
                         .decorator(delegate -> (ctx, req) -> HttpResponse.of("from-lambda"))
                         // outer: SimpleDecoratingHttpClient
                         .decorator(delegate -> new SimpleDecoratingHttpClient(delegate) {
                             @Override
                             public HttpResponse execute(ClientRequestContext ctx, HttpRequest req)
                                     throws Exception {
                                 return unwrap().execute(ctx, req);
                             }
                         })
                         .build()
                         .blocking();

        final AggregatedHttpResponse response = client.get("http://127.0.0.1/");
        assertThat(response.contentUtf8()).isEqualTo("from-lambda");
    }

    @Test
    void multipleLambdaDecorators() {
        final BlockingWebClient client =
                WebClient.builder()
                         .decorator(delegate -> (ctx, req) -> HttpResponse.of("first"))
                         .decorator(delegate -> (ctx, req) -> delegate.execute(ctx, req))
                         .decorator(delegate -> (ctx, req) -> delegate.execute(ctx, req))
                         .build()
                         .blocking();

        final AggregatedHttpResponse response = client.get("http://127.0.0.1/");
        assertThat(response.contentUtf8()).isEqualTo("first");
    }

    @Test
    void decoratorWrappingWrongDelegate() {
        // A decorator that wraps a different client than the one passed to it.
        // The as() chain goes through the wrong path, so TailHttpClient is not found.
        final HttpClient other = (ctx, req) -> HttpResponse.of(HttpStatus.OK);
        assertThatThrownBy(() -> {
            WebClient.builder()
                     .decorator(delegate -> new SimpleDecoratingHttpClient(other) {
                         @Override
                         public HttpResponse execute(ClientRequestContext ctx, HttpRequest req)
                                 throws Exception {
                             return unwrap().execute(ctx, req);
                         }
                     })
                     .build();
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Failed to find TailHttpClient");
    }
}
