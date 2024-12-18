/*
 * Copyright 2023 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.internal.client.PendingExceptionUtil;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class PendingExceptionTest {

    @Nullable
    private static CompletableFuture<Void> whenPendingExceptionSet;

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();
            sb.connectionDrainDurationMicros(0);
            sb.decorator(LoggingService.newDecorator());
            sb.service("/", (ctx, req) -> {
                return HttpResponse.of(req.aggregate().thenApply(agg -> {
                    ctx.log().ensureAvailable(RequestLogProperty.SESSION).channel().close();
                    return HttpResponse.streaming();
                }));
            });
        }
    };

    @EnumSource(value = SessionProtocol.class, mode = Mode.EXCLUDE, names = {"PROXY", "UNDEFINED"})
    @ParameterizedTest
    void shouldPropagatePendingException(SessionProtocol protocol) {
        final AnticipatedException pendingException = new AnticipatedException();
        whenPendingExceptionSet = new CompletableFuture<>();
        try (ClientFactory factory = ClientFactory.builder()
                                                   .tlsNoVerify()
                                                   .idleTimeoutMillis(1000)
                                                   .build()) {
            final BlockingWebClient client =
                    WebClient.builder(server.uri(protocol))
                             .factory(factory)
                             .decorator((delegate, ctx, req) -> {
                                 ctx.log().whenAvailable(RequestLogProperty.SESSION).thenAccept(log -> {
                                     PendingExceptionUtil.setPendingException(log.channel(), pendingException);
                                     whenPendingExceptionSet.complete(null);
                                 });
                                 return delegate.execute(ctx, req);
                             })
                             .build()
                             .blocking();

            assertThatThrownBy(() -> client.get("/"))
                    .isInstanceOfAny(ClosedSessionException.class, ClosedStreamException.class)
                    .hasCause(pendingException);
        }
    }
}
