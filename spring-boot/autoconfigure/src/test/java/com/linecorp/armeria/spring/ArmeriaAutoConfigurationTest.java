/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.spring;

import static net.javacrumbs.jsonunit.JsonMatchers.jsonPartMatches;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;

import java.util.Collection;
import java.util.Collections;

import javax.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.http.HttpClient;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.common.http.HttpSessionProtocols;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.http.AbstractHttpService;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.spring.ArmeriaAutoConfigurationTest.TestConfiguration;
import com.linecorp.armeria.spring.test.thrift.main.HelloService;
import com.linecorp.armeria.spring.test.thrift.main.HelloService.hello_args;

import io.netty.util.AsciiString;

/**
 * This uses {@link ArmeriaAutoConfiguration} for integration tests.
 * application-autoConfTest.yml will be loaded with minimal settings to make it work.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "autoConfTest" })
@DirtiesContext
public class ArmeriaAutoConfigurationTest {

    @SpringBootApplication
    public static class TestConfiguration {

        @Bean
        public HttpServiceRegistrationBean okService() {
            return new HttpServiceRegistrationBean()
                    .setServiceName("okService")
                    .setService(new OkService())
                    .setPathMapping(PathMapping.ofExact("/ok"));
        }

        @Bean
        public ThriftServiceRegistrationBean helloThriftService() {
            return new ThriftServiceRegistrationBean()
                    .setServiceName("helloService")
                    .setService(THttpService.of((HelloService.Iface) name -> "hello " + name))
                    .setPath("/thrift")
                    .setExampleRequests(Collections.singleton(new hello_args("nameVal")))
                    .setExampleHeaders(Collections.singleton(HttpHeaders.of(
                            AsciiString.of("x-additional-header"), "headerVal")));
        }
    }

    public static class OkService extends AbstractHttpService {
        @Override
        protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
                throws Exception {
            res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "ok");
        }
    }

    @Inject
    private Server server;

    private String newUrl(String scheme) {
        final int port = server.activePort().get().localAddress().getPort();
        return scheme + "://127.0.0.1:" + port;
    }

    @Test
    public void testHttpServiceRegistrationBean() throws Exception {
        final int port = server.activePort().get().localAddress().getPort();
        HttpClient client = Clients.newClient(newUrl("none+h1c"), HttpClient.class);

        HttpResponse response = client.execute(HttpRequest.of(HttpHeaders.of(HttpMethod.GET, "/ok")));

        AggregatedHttpMessage msg = response.aggregate().get();
        assertThat(msg.status()).isEqualTo(HttpStatus.OK);
        assertThat(msg.content().array()).isEqualTo("ok".getBytes());
    }

    @Test
    public void testThriftServiceRegistrationBean() throws Exception {
        HelloService.Iface client = Clients.newClient(newUrl("tbinary+h1c") + "/thrift",
                                                      HelloService.Iface.class);

        assertThat(client.hello("world")).isEqualTo("hello world");

        HttpClient httpClient = Clients.newClient(newUrl("none+h1c"), HttpClient.class);
        HttpResponse response =
                httpClient.execute(HttpRequest.of(HttpHeaders.of(HttpMethod.GET,
                                                                 "/internal/docs/specification.json")));

        AggregatedHttpMessage msg = response.aggregate().get();
        assertThat(msg.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(msg.content().toStringAscii()).matches(
                jsonPartMatches("services[0].exampleHttpHeaders[0].x-additional-header",
                                is("headerVal")));
    }

    @Test
    public void testPortConfiguration() throws Exception {
        final Collection<ServerPort> ports = server.activePorts().values();
        assertThat(ports.stream().filter(p -> p.protocol() == HttpSessionProtocols.HTTP)).hasSize(3);
        assertThat(ports.stream().filter(p -> p.localAddress().getAddress().isAnyLocalAddress())).hasSize(2);
        assertThat(ports.stream().filter(p -> p.localAddress().getAddress().isLoopbackAddress())).hasSize(1);
    }
}
