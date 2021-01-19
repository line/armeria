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

package com.linecorp.armeria.internal.client.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.grpc.GrpcClientOptions;
import com.linecorp.armeria.client.grpc.GrpcClientStubFactory;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceStub;

import io.grpc.Channel;
import io.grpc.ServiceDescriptor;

class CustomGrpcClientFactoryTest {
    @Test
    void customFactory() {
        final AtomicBoolean invoked = new AtomicBoolean();
        final TestServiceStub client =
                Clients.builder("gproto+http://127.0.0.1")
                       .option(GrpcClientOptions.GRPC_CLIENT_STUB_FACTORY.newValue(new GrpcClientStubFactory() {
                           @Override
                           public ServiceDescriptor findServiceDescriptor(Class<?> clientType) {
                               invoked.set(true);
                               return TestServiceGrpc.getServiceDescriptor();
                           }

                           @Override
                           public Object newClientStub(Class<?> clientType, Channel channel) {
                               return TestServiceGrpc.newStub(channel);
                           }
                       }))
                       .build(TestServiceStub.class);

        assertThat(client).isNotNull();
        assertThat(invoked).isTrue();
    }

    @Test
    void illegalType() {
        final AtomicBoolean invoked = new AtomicBoolean();
        assertThatThrownBy(() -> {
            Clients.builder("gproto+http://127.0.0.1")
                   .option(GrpcClientOptions.GRPC_CLIENT_STUB_FACTORY.newValue(new GrpcClientStubFactory() {
                       @Override
                       public ServiceDescriptor findServiceDescriptor(Class<?> clientType) {
                           invoked.set(true);
                           return TestServiceGrpc.getServiceDescriptor();
                       }

                       @Override
                       public Object newClientStub(Class<?> clientType, Channel channel) {
                           return TestServiceGrpc.newBlockingStub(channel);
                       }
                   }))
                   .build(TestServiceStub.class);
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Unexpected client stub type: " +
                                TestServiceGrpc.TestServiceBlockingStub.class.getName());
    }
}
