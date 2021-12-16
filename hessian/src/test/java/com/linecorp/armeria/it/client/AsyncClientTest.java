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

package com.linecorp.armeria.it.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

import com.caucho.hessian.client.HessianConnectionException;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.hessian.service.HelloRequest;
import com.linecorp.armeria.hessian.service.HelloResponse;
import com.linecorp.armeria.hessian.service.HelloServiceFutureStub;

/**
 * test send and receive.
 *
 * @author eisig
 */
@SpringBootTest(classes = { HessianConfiguration.class },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnableAutoConfiguration
class AsyncClientTest {

    @Autowired
    private ServletWebServerApplicationContext webServerAppCtxt;

    private int servicePort() {
        return webServerAppCtxt.getWebServer().getPort();
    }

    @Test
    void testSend() throws ExecutionException, InterruptedException {
        final HelloServiceFutureStub helloService = helloService("/services/helloService.hs");
        final String response = helloService.sayHello().join();
        assertThat(response).isEqualTo("Hello");
        final HelloResponse response2 = helloService.sayHello2(new HelloRequest("Jack")).toCompletableFuture()
                                                    .join();
        assertThat(response2.getMessage()).isEqualTo("Hello Jack");
    }

    @Test
    void testUnknownUrl() {
        final HelloServiceFutureStub helloService = helloService("/services/helloService2.hs");
        assertThatThrownBy(() -> helloService.sayHello().get()).hasCauseInstanceOf(
                HessianConnectionException.class);
    }

    private HelloServiceFutureStub helloService(String path) {
        return Clients.newClient("hessian+http://127.0.0.1:" + servicePort() + path,
                                 HelloServiceFutureStub.class);
    }
}
