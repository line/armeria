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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceBlockingStub;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.MethodDescriptor;

class GrpcClientBuilderTest {

    @Test
    void defaultSerializationFormat() {
        TestServiceBlockingStub client =
                GrpcClients.builder("http://foo.com").build(TestServiceBlockingStub.class);
        ClientBuilderParams clientParams = Clients.unwrap(client, ClientBuilderParams.class);
        assertThat(clientParams.uri().getScheme())
                .startsWith(GrpcSerializationFormats.PROTO.uriText());

        client = GrpcClients.builder("none+http", EndpointGroup.of()).build(TestServiceBlockingStub.class);
        clientParams = Clients.unwrap(client, ClientBuilderParams.class);
        assertThat(clientParams.uri().getScheme())
                .startsWith(GrpcSerializationFormats.PROTO.uriText());
    }

    @Test
    void customSerializationFormat() {
        final TestServiceBlockingStub client =
                GrpcClients.builder("gjson+http://foo.com").build(TestServiceBlockingStub.class);

        final ClientBuilderParams clientParams = Clients.unwrap(client, ClientBuilderParams.class);
        assertThat(clientParams.uri().getScheme())
                .startsWith(GrpcSerializationFormats.JSON.uriText());
    }

    @Test
    void setSerializationFormat() {
        final TestServiceBlockingStub client =
                GrpcClients.builder("http://foo.com")
                           .serializationFormat(GrpcSerializationFormats.JSON)
                           .build(TestServiceBlockingStub.class);
        final ClientBuilderParams clientParams = Clients.unwrap(client, ClientBuilderParams.class);
        assertThat(clientParams.uri().getScheme())
                .startsWith(GrpcSerializationFormats.JSON.uriText());

        assertThatThrownBy(() -> GrpcClients.builder("http://foo.com")
                                            .serializationFormat(SerializationFormat.UNKNOWN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serializationFormat: ");
    }

    @Test
    void invalidSerializationFormat() {
        assertThatThrownBy(() -> GrpcClients.builder("unknown+http://foo.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void prefix() {
        final TestServiceBlockingStub client =
                GrpcClients.builder("http://foo.com")
                           .pathPrefix("/bar")
                           .build(TestServiceBlockingStub.class);
        final ClientBuilderParams clientParams = Clients.unwrap(client, ClientBuilderParams.class);
        assertThat(clientParams.uri().toString()).isEqualTo("gproto+http://foo.com/bar/");

        assertThatThrownBy(() -> {
            GrpcClients.builder("http://foo.com")
                       .pathPrefix("bar")
                       .build(TestServiceBlockingStub.class);
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("prefix: bar (must start with '/')");
    }

    @Test
    void messageLength() {
        final int maxRequestMessageLength = 10;
        final int maxResponseMessageLength = 20;
        final TestServiceBlockingStub client =
                GrpcClients.builder("http://foo.com")
                           .maxRequestMessageLength(maxRequestMessageLength)
                           .maxResponseMessageLength(maxResponseMessageLength)
                           .build(TestServiceBlockingStub.class);
        final ClientBuilderParams clientParams = Clients.unwrap(client, ClientBuilderParams.class);
        assertThat(clientParams.options().get(GrpcClientOptions.MAX_INBOUND_MESSAGE_SIZE_BYTES))
                .isEqualTo(maxResponseMessageLength);

        assertThat(clientParams.options().get(GrpcClientOptions.MAX_OUTBOUND_MESSAGE_SIZE_BYTES))
                .isEqualTo(maxRequestMessageLength);
    }

    @Test
    void enableUnsafeWrapResponseBuffers() {
        final TestServiceBlockingStub client =
                GrpcClients.builder("http://foo.com")
                           .enableUnsafeWrapResponseBuffers(true)
                           .build(TestServiceBlockingStub.class);
        final ClientBuilderParams clientParams = Clients.unwrap(client, ClientBuilderParams.class);
        assertThat(clientParams.options().get(GrpcClientOptions.UNSAFE_WRAP_RESPONSE_BUFFERS)).isTrue();
    }

    @Test
    void intercept() {
        final ClientInterceptor interceptorA = new ClientInterceptor() {
            @Override
            public <I, O> ClientCall<I, O> interceptCall(MethodDescriptor<I, O> method,
                                                         CallOptions callOptions, Channel next) {
                return next.newCall(method, callOptions);
            }
        };

        final ClientInterceptor interceptorB = new ClientInterceptor() {
            @Override
            public <I, O> ClientCall<I, O> interceptCall(MethodDescriptor<I, O> method,
                                                         CallOptions callOptions, Channel next) {
                return next.newCall(method, callOptions);
            }
        };

        final TestServiceBlockingStub client =
                GrpcClients.builder("http://foo.com")
                           .intercept(interceptorA)
                           .intercept(interceptorB)
                           .build(TestServiceBlockingStub.class);

        final ClientBuilderParams clientParams = Clients.unwrap(client, ClientBuilderParams.class);
        assertThat(clientParams.options().get(GrpcClientOptions.INTERCEPTORS))
                .containsExactly(interceptorA, interceptorB);
    }
}
