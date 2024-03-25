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
package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.endpoint.EmptyEndpointGroupException;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.client.ClientThreadLocalState;

class ClientRequestContextInitFailureTest {
    @Test
    void endpointSelectionFailure() {
        assertFailure(EndpointGroup.of(), actualCause -> {
            assertThat(actualCause).isInstanceOf(EmptyEndpointGroupException.class);
        });
    }

    @Test
    void threadLocalCustomizerFailure() {
        final RuntimeException cause = new RuntimeException();
        try (SafeCloseable ignored = Clients.withContextCustomizer(ctx -> {
            throw cause;
        })) {
            assertFailure(Endpoint.of("127.0.0.1", 1), actualCause -> {
                assertThat(actualCause).isSameAs(cause);
            });
        }

        // Thread-local state must be cleaned up.
        assertThat(ClientThreadLocalState.get()).isNull();
    }

    private static void assertFailure(EndpointGroup endpointGroup, Consumer<Throwable> requirements) {
        final AtomicReference<ClientRequestContext> capturedCtx = new AtomicReference<>();
        final WebClient client = WebClient.builder(SessionProtocol.HTTP, endpointGroup)
                                          .decorator((delegate, ctx, req) -> {
                                              capturedCtx.set(ctx);
                                              return delegate.execute(ctx, req);
                                          }).build();

        final Throwable actualCause = catchThrowable(() -> client.get("/").aggregate().join()).getCause();
        assertThat(actualCause).isInstanceOf(UnprocessedRequestException.class);
        assertThat(actualCause.getCause()).satisfies((Consumer) requirements);

        assertThat(capturedCtx.get()).satisfies(ctx -> {
            final RequestLog log = ctx.log().ensureComplete();
            assertThat(log.requestCause()).isSameAs(actualCause);
            assertThat(log.responseCause()).isSameAs(actualCause);
        });
    }
}
