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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.CompletableRpcResponse;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingRpcService;

import testing.thrift.main.HelloService.AsyncIface;

class THttpClientTest {

    @Test
    void serviceAddedIsCalled() {
        final AtomicReference<ServiceConfig> cfgHolder = new AtomicReference<>();
        final THttpService tHttpService =
                ThriftCallService.of((AsyncIface) (name, cb) -> cb.onComplete("name"))
                                 .decorate(delegate -> new SimpleDecoratingRpcService(delegate) {
                                     @Override
                                     public void serviceAdded(ServiceConfig cfg) throws Exception {
                                         cfgHolder.set(cfg);
                                     }

                                     @Override
                                     public RpcResponse serve(
                                             ServiceRequestContext ctx, RpcRequest req) throws Exception {
                                         return new CompletableRpcResponse();
                                     }
                                 }).decorate(THttpService.newDecorator());
        Server.builder().service("/", tHttpService).build();

        final ServiceConfig serviceConfig = cfgHolder.get();
        assertThat(serviceConfig).isNotNull();
        assertThat(serviceConfig.service()).isInstanceOf(THttpService.class);

        final ThriftCallService thriftCallService = tHttpService.as(ThriftCallService.class);
        assertThat(thriftCallService).isNotNull();
    }
}
