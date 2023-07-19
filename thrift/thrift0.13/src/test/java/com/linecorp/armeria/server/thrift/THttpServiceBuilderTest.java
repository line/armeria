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

package com.linecorp.armeria.server.thrift;

import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.BINARY;
import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import org.apache.thrift.TException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingRpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import testing.thrift.main.FooService;
import testing.thrift.main.FooService.AsyncIface;
import testing.thrift.main.FooServiceException;
import testing.thrift.main.HelloService;

class THttpServiceBuilderTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final AsyncIface service1 = mock(AsyncIface.class);
            doThrow(new IllegalStateException()).when(service1).bar1(any());
            doThrow(new IllegalArgumentException()).when(service1).bar2(any());
            final AsyncIface service2 = mock(AsyncIface.class);
            doThrow(new FooServiceException("Foo Bar Qux")).when(service2).bar1(any());

            final THttpService httpService1 = THttpService
                    .builder()
                    .addService(service1)
                    .exceptionHandler((ctx, cause) -> {
                        if (cause instanceof IllegalStateException) {
                            return RpcResponse.ofFailure(
                                    new FooServiceException("Illegal state!"));
                        }
                        if (cause instanceof IllegalArgumentException) {
                            return RpcResponse.of("I'm generous");
                        }
                        return RpcResponse.ofFailure(cause);
                    })
                    .build();

            final THttpService httpService2 = THttpService
                    .builder()
                    .addService(service2)
                    .decorate(delegate -> new SimpleDecoratingRpcService(delegate) {
                        @Override
                        public RpcResponse serve(ServiceRequestContext ctx, RpcRequest req) throws Exception {
                            return RpcResponse.from(
                                    ctx.makeContextAware(UnmodifiableFuture.completedFuture(new Object()))
                                       .thenCompose(userInfo -> {
                                           try {
                                               return unwrap().serve(ctx, req);
                                           } catch (Exception e) {
                                               return RpcResponse.ofFailure(e);
                                           }
                                       }));
                        }
                    })
                    .build();

            sb.service("/exception", httpService1);
            sb.service("/rpc-exception", httpService2);
        }
    };

    @Test
    void exceptionHandler() throws TException {
        final FooService.Iface client = ThriftClients.builder(server.httpUri())
                                                     .path("/exception")
                                                     .build(FooService.Iface.class);
        final Throwable thrown = catchThrowable(client::bar1);
        assertThat(thrown).isInstanceOf(FooServiceException.class);
        assertThat(((FooServiceException) thrown).getStringVal()).isEqualTo("Illegal state!");

        assertThat(client.bar2()).isEqualTo("I'm generous");
    }

    @Test
    void exceptionHandler_Test() throws TException {
        final FooService.Iface client = ThriftClients.builder(server.httpUri())
                                                     .path("/rpc-exception")
                                                     .build(FooService.Iface.class);
        final Throwable thrown = catchThrowable(client::bar1);
        assertThat(thrown).isInstanceOf(FooServiceException.class);
        assertThat(((FooServiceException) thrown).getStringVal()).isEqualTo("Foo Bar Qux");
    }

    @Test
    void testOtherSerializations_WhenUserSpecifies_ShouldNotUseDefaults() {
        final THttpService service = THttpService.builder().addService((HelloService.Iface) name -> name)
                                                 .defaultSerializationFormat(BINARY)
                                                 .otherSerializationFormats(JSON)
                                                 .build();

        assertThat(service.supportedSerializationFormats()).containsExactly(BINARY, JSON);
    }

    @Test
    void testOtherSerializations_WhenUserDoesNotSpecify_ShouldUseDefaults() {
        final THttpService service = THttpService.builder().addService((HelloService.Iface) name -> name)
                                                 .defaultSerializationFormat(JSON)
                                                 .build();

        assertThat(service.supportedSerializationFormats())
                .containsExactlyInAnyOrderElementsOf(ThriftSerializationFormats.values());
    }
}
