/*
 * Copyright 2020 LINE Corporation
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

import org.apache.thrift.TException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class ThriftServiceLogNameTest {

    private static final HelloService.AsyncIface HELLO_SERVICE_HANDLER =
            (name, resultHandler) -> resultHandler.onComplete("Hello " + name);

    private static ServiceRequestContext capturedCtx;
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/thrift", THttpService.builder()
                                                         .addService(HELLO_SERVICE_HANDLER)
                                                         .build());
        }
    };

    @Test
    void logName() throws TException {
        final HelloService.Iface client =
                Clients.builder(server.httpUri(ThriftSerializationFormats.BINARY).resolve("/thrift"))
                       .build(HelloService.Iface.class);

        client.hello("hello");
        final RequestLog log = capturedCtx.log().partial();
        assertThat(log.name()).isEqualTo("hello");
        assertThat(log.serviceName()).isEqualTo(HelloService.AsyncIface.class.getName());
        assertThat(log.fullName()).isEqualTo(HelloService.AsyncIface.class.getName() + "/hello");
    }
}
