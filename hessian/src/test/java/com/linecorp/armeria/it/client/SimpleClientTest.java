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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

import com.caucho.hessian.client.HessianConnectionException;
import com.caucho.hessian.client.HessianProxyFactory;
import com.google.common.io.CharStreams;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.hessian.HessianClientOptions;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.hessian.service.DemoException;
import com.linecorp.armeria.hessian.service.HelloRequest;
import com.linecorp.armeria.hessian.service.HelloResponse;
import com.linecorp.armeria.hessian.service.HelloService;

/**
 * test send and receive.
 *
 * @author eisig
 */
@SpringBootTest(classes = { HessianConfiguration.class },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnableAutoConfiguration
class SimpleClientTest {

    enum HessianVersion {

        request2_reply2, request1_reply2,
        // request1_reply1,
    }

    @Autowired
    private ServletWebServerApplicationContext webServerAppCtxt;

    private int servicePort() {
        return webServerAppCtxt.getWebServer().getPort();
    }

    @ParameterizedTest
    @EnumSource(HessianVersion.class)
    void testSend(HessianVersion version) {
        final HelloService helloService = helloService("/services/helloService.hs", version);
        final String response = helloService.sayHello();
        assertThat(response).isEqualTo("Hello");
        final HelloResponse response2 = helloService.sayHello2(new HelloRequest("Jack"));
        assertThat(response2.getMessage()).isEqualTo("Hello Jack");
    }

    @ParameterizedTest
    @EnumSource(HessianVersion.class)
    void testStreamReply(HessianVersion version) throws IOException {
        final HelloService helloService = helloService("/services/helloService.hs", version);
        try (InputStream inputStream = helloService.replySteam(new HelloRequest("one"))) {
            final String response = CharStreams.toString(new InputStreamReader(inputStream));
            assertThat(response).isEqualTo("Hello one");
        }

        try (InputStream inputStream = helloService.replySteam(new HelloRequest("two"))) {
            final String response = CharStreams.toString(new InputStreamReader(inputStream));
            assertThat(response).isEqualTo("Hello two");
        }
    }

    @Test
    void testServerSideException() {
        final HelloService helloService = helloService("/services/helloService.hs",
                                                       HessianVersion.request2_reply2);

        assertThatThrownBy(() -> helloService.failedSayHello(new HelloRequest("error")))
                .isInstanceOf(DemoException.class)
                // pass server exception.
                .hasMessageStartingWith("failed");
    }

    @Test
    void testResponseTimeoutException() {
        final ClientBuilder builder = Clients
                .builder("hessian+http://127.0.0.1:" + servicePort() + "/services/helloService.hs");
        final HelloService helloService = builder.responseTimeoutMillis(300).build(HelloService.class);
        assertThatThrownBy(() -> helloService.delaySayHello(new HelloRequest("error")))
                .isInstanceOf(ResponseTimeoutException.class);
    }

    @Test
    void tetLargeReponse() {
        final ClientBuilder builder = Clients
                .builder("hessian+http://127.0.0.1:" + servicePort() + "/services/helloService.hs");
        final HelloService helloService = builder.maxResponseLength(1000_000_000).responseTimeoutMillis(10000)
                                                 .build(HelloService.class);
        final HelloResponse resp = helloService.largeResponse(new HelloRequest("larger"));

        assertThat(resp).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(HessianVersion.class)
    void testUnknownUrl(HessianVersion version) {
        final HelloService helloService = helloService("/services/helloService2.hs", version);
        assertThatThrownBy(helloService::sayHello).isInstanceOf(HessianConnectionException.class)
                                                  .hasMessageStartingWith("status code: 404");
    }

    private HelloService helloService(String path, HessianVersion version) {
        final ClientBuilder builder = Clients.builder("hessian+http://127.0.0.1:" + servicePort() + path);
        switch (version) {
            case request1_reply2:
                builder.options(HessianClientOptions.HESSIAN2_REQUEST.newValue(Boolean.FALSE));
                break;
            case request2_reply2:
                break;
            // case request1_reply1:
            // builder.options(HessianClientOptions.HESSIAN2_REQUEST.newValue(Boolean.FALSE))
            // .options(HessianClientOptions.HESSIAN2_REPLY.newValue(Boolean.FALSE));
            // break;
        }
        return builder.build(HelloService.class);
    }

    private HelloService helloServiceOld(String path, HessianVersion version) {
        final HessianProxyFactory factory = new HessianProxyFactory();
        switch (version) {
            case request1_reply2:
                factory.setHessian2Request(false);
                break;
            default:
                break;
        }
        try {
            return (HelloService) factory.create(HelloService.class,
                                                 "http://127.0.0.1:" + servicePort() + path);
        } catch (MalformedURLException e) {
            return Exceptions.throwUnsafely(e);
        }
    }
}
