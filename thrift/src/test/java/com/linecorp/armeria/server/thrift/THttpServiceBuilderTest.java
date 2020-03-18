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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingRpcService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.service.test.thrift.main.HelloService.Iface;

class THttpServiceBuilderTest {

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

    @Test
    void validateDecorator() {
        THttpService.builder()
                    .addService((Iface) name -> name)
                    .decorate(delegate -> new SimpleDecoratingRpcService(delegate) {
                        @Override
                        public RpcResponse serve(ServiceRequestContext ctx, RpcRequest req) throws Exception {
                            return delegate().serve(ctx, req);
                        }
                    })
                    .build();

        assertThatThrownBy(() -> THttpService.builder()
                                             .addService((Iface) name -> name)
                                             .decorate(Function.identity())
                                             .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decorator should override Service.as()");
    }
}
