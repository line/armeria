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
package com.linecorp.armeria.client.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroupException;
import com.linecorp.armeria.client.endpoint.EndpointGroupRegistry;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceBlockingStub;
import com.linecorp.armeria.protobuf.EmptyProtos.Empty;

import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;

class GrpcClientRequestContextInitFailureTest {
    @Test
    void missingEndpointGroup() {
        assertFailure("group:none", actualCause -> {
            assertThat(actualCause).isInstanceOf(EndpointGroupException.class)
                                   .hasMessageContaining("non-existent");
        });
    }

    @Test
    void endpointSelectionFailure() {
        EndpointGroupRegistry.register("foo", EndpointGroup.empty(), EndpointSelectionStrategy.ROUND_ROBIN);
        try {
            assertFailure("group:foo", actualCause -> {
                assertThat(actualCause).isInstanceOf(EndpointGroupException.class)
                                       .hasMessageContaining("empty");
            });
        } finally {
            EndpointGroupRegistry.unregister("foo");
        }
    }

    @Test
    void threadLocalCustomizerFailure() {
        final RuntimeException cause = new RuntimeException();
        try (SafeCloseable ignored = Clients.withContextCustomizer(ctx -> {
            throw cause;
        })) {
            assertFailure("127.0.0.1:1", actualCause -> {
                assertThat(actualCause).isSameAs(cause);
            });
        }
    }

    private static void assertFailure(String authority, Consumer<Throwable> requirements) {
        final AtomicReference<ClientRequestContext> capturedCtx = new AtomicReference<>();
        final TestServiceBlockingStub client =
                Clients.builder("gproto+http://" + authority)
                       .decorator((delegate, ctx, req) -> {
                           capturedCtx.set(ctx);
                           return delegate.execute(ctx, req);
                       })
                       .build(TestServiceBlockingStub.class);

        final Throwable grpcCause = catchThrowable(() -> client.emptyCall(Empty.getDefaultInstance()));
        assertThat(grpcCause).isInstanceOfSatisfying(StatusRuntimeException.class, cause -> {
            assertThat(cause.getStatus().getCode()).isSameAs(Code.UNAVAILABLE);
        });

        final Throwable actualCause = grpcCause.getCause();
        assertThat(actualCause).isInstanceOf(UnprocessedRequestException.class);
        assertThat(actualCause.getCause()).satisfies((Consumer) requirements);

        assertThat(capturedCtx.get()).satisfies(ctx -> {
            ctx.log().ensureAvailability(RequestLogAvailability.COMPLETE);
            assertThat(ctx.log().requestCause()).isSameAs(actualCause);
            assertThat(ctx.log().responseCause()).isSameAs(actualCause);
        });
    }
}
