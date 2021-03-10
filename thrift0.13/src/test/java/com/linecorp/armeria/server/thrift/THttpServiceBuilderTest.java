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

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.service.test.thrift.main.FooService;
import com.linecorp.armeria.service.test.thrift.main.FooService.AsyncIface;
import com.linecorp.armeria.service.test.thrift.main.FooServiceException;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class THttpServiceBuilderTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final AsyncIface service = mock(AsyncIface.class);
            doThrow(new IllegalStateException()).when(service).bar1(any());
            sb.service("/exception", THttpService.builder()
                                                 .addService(service)
                                                 .exceptionTranslator((ctx, cause) -> {
                                                     if (cause instanceof IllegalStateException) {
                                                         return new FooServiceException("Illegal state!");
                                                     }
                                                     return cause;
                                                 })
                                                 .build());
        }
    };

    @Test
    void translatedException() throws TException {
        final FooService.Iface client =
                Clients.builder(server.uri(SessionProtocol.HTTP, BINARY)
                                      .resolve("/exception"))
                       .build(FooService.Iface.class);
        final Throwable thrown = catchThrowable(client::bar1);
        assertThat(thrown).isInstanceOf(FooServiceException.class);
        assertThat(((FooServiceException) thrown).getStringVal()).isEqualTo("Illegal state!");
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
