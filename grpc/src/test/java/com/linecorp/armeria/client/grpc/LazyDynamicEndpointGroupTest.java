/*
 * Copyright 2021 LINE Corporation
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
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EmptyEndpointGroupException;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.endpoint.EndpointSelector;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceStub;
import com.linecorp.armeria.internal.common.grpc.TestServiceImpl;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

class LazyDynamicEndpointGroupTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(GrpcService.builder()
                                  .addService(new TestServiceImpl(Executors.newSingleThreadScheduledExecutor()))
                                  .build());
        }
    };

    @Test
    void emptyEndpoint() {
        final EndpointGroup endpointGroup = new DynamicEndpointGroup();
        final TestServiceStub client =
                GrpcClients.builder(SessionProtocol.HTTP, endpointGroup)
                           .build(TestServiceStub.class);

        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicReference<Throwable> causeRef = new AtomicReference<>();

        client.unaryCall(SimpleRequest.getDefaultInstance(),
                         new StreamObserver<SimpleResponse>() {
                             @Override
                             public void onNext(SimpleResponse value) {}

                             @Override
                             public void onError(Throwable t) {
                                 causeRef.set(t);
                                 completed.set(true);
                             }

                             @Override
                             public void onCompleted() {}
                         });

        // A call does not immediately fail.
        await().untilTrue(completed);
        assertThat(causeRef.get()).isInstanceOf(StatusRuntimeException.class)
                                  .hasCauseInstanceOf(UnprocessedRequestException.class)
                                  .hasRootCauseInstanceOf(EmptyEndpointGroupException.class);
    }

    @Test
    void initializeWithEmptyEndpoint() {
        final LazyEndpointGroup endpointGroup = new LazyEndpointGroup();
        endpointGroup.setAll(ImmutableList.of());
        final TestServiceStub client =
                GrpcClients.builder(SessionProtocol.HTTP, endpointGroup)
                           .build(TestServiceStub.class);

        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicReference<Throwable> causeRef = new AtomicReference<>();

        client.unaryCall(SimpleRequest.getDefaultInstance(),
                         new StreamObserver<SimpleResponse>() {
                             @Override
                             public void onNext(SimpleResponse value) {}

                             @Override
                             public void onError(Throwable t) {
                                 causeRef.set(t);
                                 completed.set(true);
                             }

                             @Override
                             public void onCompleted() {}
                         });

        // A call does not immediately fail.
        await().untilTrue(completed);
        assertThat(causeRef.get()).isInstanceOf(StatusRuntimeException.class)
                                  .hasCauseInstanceOf(UnprocessedRequestException.class)
                                  .hasRootCauseInstanceOf(EmptyEndpointGroupException.class);
    }

    @Test
    void lazyEndpoint() {
        final LazyEndpointGroup endpointGroup = new LazyEndpointGroup();
        final TestServiceStub client =
                GrpcClients.builder(Scheme.of(GrpcSerializationFormats.PROTO, SessionProtocol.HTTP),
                                    endpointGroup)
                           .decorator(LoggingClient.newDecorator())
                           .build(TestServiceStub.class);

        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicReference<Throwable> causeRef = new AtomicReference<>();
        final AtomicReference<SimpleResponse> responseRef = new AtomicReference<>();

        client.unaryCall(SimpleRequest.getDefaultInstance(),
                         new StreamObserver<SimpleResponse>() {
                             @Override
                             public void onNext(SimpleResponse value) {
                                 responseRef.set(value);
                             }

                             @Override
                             public void onError(Throwable t) {
                                 causeRef.set(t);
                             }

                             @Override
                             public void onCompleted() {
                                 completed.set(true);
                             }
                         });
        assertThat(completed).isFalse();
        assertThat(causeRef.get()).isNull();

        endpointGroup.add(server.httpEndpoint());

        await().untilAtomic(completed, Matchers.is(true));
        assertThat(responseRef.get()).isNotNull();
    }

    /**
     * Reproduce a race case about an endpoint selection that occurs if the following two conditions are met.
     * 1) An EndpointGroup resolved and has non-empty endpoints when creating a ArmeriaClientCall.
     * 2) The endpoints in the EndpointGroup become empty when the ArmeriaClientCall initializing context.
     */
    @Test
    void testNullSelectedEndpoint() {
        final EndpointSelector nullEndpointSelector = new EndpointSelector() {
            @Nullable
            @Override
            public Endpoint selectNow(ClientRequestContext ctx) {
                // Intentionally return null to simulate that the EndpointGroup become empty when
                // initializing context. for case 2)
                return null;
            }

            @Override
            public CompletableFuture<Endpoint> select(ClientRequestContext ctx,
                                                      ScheduledExecutorService executor, long timeoutMillis) {
                final CompletableFuture<Endpoint> future = new CompletableFuture<>();
                future.completeExceptionally(new AnticipatedException("no endpoint"));
                return future;
            }
        };

        final EndpointSelectionStrategy strategy = endpointGroup -> nullEndpointSelector;
        final LazyEndpointGroup endpointGroup = new LazyEndpointGroup(strategy);
        // Make non-empty EndpointGroup for case 1)
        endpointGroup.add(Endpoint.of("foo"));

        final TestServiceStub client =
                GrpcClients.builder(SessionProtocol.HTTP, endpointGroup)
                           .build(TestServiceStub.class);

        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicReference<Throwable> causeRef = new AtomicReference<>();

        client.unaryCall(SimpleRequest.getDefaultInstance(),
                         new StreamObserver<SimpleResponse>() {
                             @Override
                             public void onNext(SimpleResponse value) {}

                             @Override
                             public void onError(Throwable t) {
                                 causeRef.set(t);
                                 completed.set(true);
                             }

                             @Override
                             public void onCompleted() {}
                         });

        // A call does not immediately fail.
        await().untilTrue(completed);
        assertThat(causeRef.get()).isInstanceOf(StatusRuntimeException.class)
                                  .hasCauseInstanceOf(UnprocessedRequestException.class)
                                  .hasRootCauseInstanceOf(AnticipatedException.class);
    }

    private static final class LazyEndpointGroup extends DynamicEndpointGroup {

        LazyEndpointGroup() {}

        LazyEndpointGroup(EndpointSelectionStrategy selectionStrategy) {
            super(selectionStrategy);
        }

        void setAll(List<Endpoint> endpoints) {
            setEndpoints(endpoints);
        }

        void add(Endpoint endpoint) {
            addEndpoint(endpoint);
        }
    }
}
