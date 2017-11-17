/*
 * Copyright 2017 LINE Corporation
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
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.logging.LoggingService;
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
                    .setPathMapping(PathMapping.ofExact("/ok"))
                    .setDecorator(LoggingService.newDecorator());
        }

        @Bean
        public AnnotatedServiceRegistrationBean annotatedService() {
            return new AnnotatedServiceRegistrationBean()
                    .setServiceName("annotatedService")
                    .setService(new AnnotatedService())
                    .setPathPrefix("/annotated")
                    .setDecorator(LoggingService.newDecorator());
        }

        @Bean
        public ThriftServiceRegistrationBean helloThriftService() {
            return new ThriftServiceRegistrationBean()
                    .setServiceName("helloService")
                    .setService(THttpService.of((HelloService.Iface) name -> "hello " + name))
                    .setPath("/thrift")
                    .setDecorator(LoggingService.newDecorator())
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

    public static class AnnotatedService {
        @Get("/get")
        public AggregatedHttpMessage get() {
            return AggregatedHttpMessage.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "annotated");
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
        HttpClient client = HttpClient.of(newUrl("h1c"));

        HttpResponse response = client.get("/ok");

        AggregatedHttpMessage msg = response.aggregate().get();
        assertThat(msg.status()).isEqualTo(HttpStatus.OK);
        assertThat(msg.content().toStringUtf8()).isEqualTo("ok");
    }

    @Test
    public void testAnnotatedServiceRegistrationBean() throws Exception {
        HttpClient client = HttpClient.of(newUrl("h1c"));

        HttpResponse response = client.get("/annotated/get");

        AggregatedHttpMessage msg = response.aggregate().get();
        assertThat(msg.status()).isEqualTo(HttpStatus.OK);
        assertThat(msg.content().toStringUtf8()).isEqualTo("annotated");
    }

    @Test
    public void testThriftServiceRegistrationBean() throws Exception {
        HelloService.Iface client = Clients.newClient(newUrl("tbinary+h1c") + "/thrift",
                                                      HelloService.Iface.class);

        assertThat(client.hello("world")).isEqualTo("hello world");

        HttpClient httpClient = HttpClient.of(newUrl("h1c"));
        HttpResponse response = httpClient.get("/internal/docs/specification.json");

        AggregatedHttpMessage msg = response.aggregate().get();
        assertThat(msg.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(msg.content().toStringUtf8()).matches(
                jsonPartMatches("services[0].exampleHttpHeaders[0].x-additional-header",
                                is("headerVal")));
    }

    @Test
    public void testPortConfiguration() throws Exception {
        final Collection<ServerPort> ports = server.activePorts().values();
        assertThat(ports.stream().filter(p -> p.protocol() == SessionProtocol.HTTP)).hasSize(3);
        assertThat(ports.stream().filter(p -> p.localAddress().getAddress().isAnyLocalAddress())).hasSize(2);
        assertThat(ports.stream().filter(p -> p.localAddress().getAddress().isLoopbackAddress())).hasSize(1);
    }

    @Test
    public void testMetrics() throws Exception {
        final String metricReport = HttpClient.of(newUrl("http"))
                                              .get("/internal/metrics")
                                              .aggregate().join()
                                              .content().toStringUtf8();
        assertThat(metricReport).contains("# TYPE jvm_gc_live_data_size_bytes gauge");
    }
}
