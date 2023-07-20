/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.it.thrift;

import static com.linecorp.armeria.server.throttling.ThrottlingStrategy.always;
import static com.linecorp.armeria.server.throttling.ThrottlingStrategy.never;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.apache.thrift.transport.TTransportException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.linecorp.armeria.client.InvalidResponseHeadersException;
import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.server.thrift.ThriftCallService;
import com.linecorp.armeria.server.throttling.ThrottlingRpcService;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

import testing.thrift.main.HelloService;

public class ThrottlingRpcServiceTest {

    @Rule
    public final ServerRule server = new ServerRule(false) {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/thrift-never", ThriftCallService.of(serviceHandler)
                                                         .decorate(ThrottlingRpcService.newDecorator(never()))
                                                         .decorate(THttpService.newDecorator()));

            sb.service("/thrift-always", ThriftCallService.of(serviceHandler)
                                                          .decorate(ThrottlingRpcService.newDecorator(always()))
                                                          .decorate(THttpService.newDecorator()));
        }
    };

    @Rule
    public MockitoRule mocks = MockitoJUnit.rule();

    @Mock
    private HelloService.Iface serviceHandler;

    @Before
    public void setUp() {
        // Start server here to avoid Rule ordering issue. Remove once
        // https://github.com/junit-team/junit4/pull/1445 will release.
        server.start();
    }

    @Test
    public void serve() throws Exception {
        final HelloService.Iface client =
                ThriftClients.newClient(server.httpUri() + "/thrift-always", HelloService.Iface.class);
        when(serviceHandler.hello("foo")).thenReturn("bar");

        assertThat(client.hello("foo")).isEqualTo("bar");
    }

    @Test
    public void throttle() throws Exception {
        final HelloService.Iface client =
                ThriftClients.newClient(server.httpUri() + "/thrift-never", HelloService.Iface.class);

        assertThatThrownBy(() -> client.hello("foo"))
                .isInstanceOf(TTransportException.class)
                .cause()
                .isInstanceOfSatisfying(InvalidResponseHeadersException.class, cause -> {
                    assertThat(cause.headers().status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
        verifyNoMoreInteractions(serviceHandler);
    }
}
