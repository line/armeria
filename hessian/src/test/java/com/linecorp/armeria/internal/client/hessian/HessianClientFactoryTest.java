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

package com.linecorp.armeria.internal.client.hessian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.URISyntaxException;

import org.junit.jupiter.api.Test;

import com.caucho.hessian.io.HessianRemoteObject;

import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.hessian.service.HelloService;

/**
 * test hessian factory.
 *
 * @author eisig
 */
class HessianClientFactoryTest {

    @Test
    void testClients() throws Exception {
        final String url = "hessian+http://127.0.0.1:8080/services/helloService.hs";
        final HelloService helloService = Clients.builder(url).decorator(LoggingClient.newDecorator())
                                                 .build(HelloService.class);

        assertThat(helloService).isNotNull();
    }

    @Test
    void testClientUrlNoPrefix() throws URISyntaxException {
        final HessianClientFactory clientFactory = new HessianClientFactory(ClientFactory.ofDefault());
        final String url = "http://127.0.0.1:8080/services/helloService.sh";
        final HelloService helloService = clientFactory.create(HelloService.class, url);
        assertThat(helloService).isNotNull();
    }

    @Test
    void testClientUrlNoPrefixFailed() throws URISyntaxException {
        final HessianClientFactory clientFactory = new HessianClientFactory(ClientFactory.ofDefault());
        final String url = "http://127.0.0.1:8080/services/helloService.sh";
        assertThatThrownBy(() -> {
            clientFactory.newClient(
                    ClientBuilderParams.of(URI.create(url), HelloService.class, ClientOptions.of()));
        }).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testHessianRemoteObjectMethod() throws Exception {
        final String url = "hessian+http://127.0.0.1:8080/services/helloService.hs";
        final HessianRemoteObject helloService =
                (HessianRemoteObject) Clients.builder(url)
                                             .decorator(
                                                     LoggingClient.newDecorator())
                                             .build(HelloService.class);

        assertThat(helloService.getHessianType()).isEqualTo(HelloService.class.getName());
        assertThat(helloService.getHessianURL())
                .isEqualTo("hessian+http://127.0.0.1:8080/services/helloService.hs");
    }
}
