/*
 * Copyright 2022 LINE Corporation
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

import static com.linecorp.armeria.internal.client.ClientPendingThrowableUtil.pendingThrowable;
import static com.linecorp.armeria.internal.client.ClientPendingThrowableUtil.removePendingThrowable;
import static com.linecorp.armeria.internal.client.ClientPendingThrowableUtil.setPendingThrowable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ClientPendingThrowableUtilTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/1", (ctx, req) -> HttpResponse.of(200));
        }
    };

    @Test
    void testPeeledException() {
        final RuntimeException e = new RuntimeException();
        final CompletionException wrapper = new CompletionException(e);
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        setPendingThrowable(ctx, wrapper);
        final Throwable throwable = pendingThrowable(ctx);
        assertThat(throwable).isEqualTo(e);
        removePendingThrowable(ctx);
        assertThat(pendingThrowable(ctx)).isNull();
    }

    @Test
    void testPendingThrowable() {
        final RuntimeException e = new RuntimeException();
        final WebClient webClient =
                WebClient.builder(SessionProtocol.HTTP, EndpointGroup.of())
                         .contextCustomizer(ctx -> setPendingThrowable(ctx, e))
                         .decorator(LoggingClient.newDecorator())
                         .build();
        assertThatThrownBy(() -> webClient.blocking().get("/"))
                .isInstanceOf(UnprocessedRequestException.class)
                .hasCause(e);
    }

    @Test
    void testPendingThrowableDerivedContext() {
        final RuntimeException e = new RuntimeException();
        final WebClient webClient =
                WebClient.builder(SessionProtocol.HTTP, EndpointGroup.of())
                         .contextCustomizer(ctx -> setPendingThrowable(ctx, e))
                         .decorator((delegate, ctx, req) -> {
                             final ClientRequestContext derived = ctx.newDerivedContext(
                                     RequestId.random(), req, null, server.httpEndpoint());
                             return delegate.execute(derived, req);
                         })
                         .build();
        assertThatThrownBy(() -> webClient.blocking().get("/"))
                .isInstanceOf(UnprocessedRequestException.class)
                .hasCause(e);
    }
}
