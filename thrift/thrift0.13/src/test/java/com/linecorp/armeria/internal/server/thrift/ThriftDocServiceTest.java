/*
 * Copyright 2016 LINE Corporation
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
package com.linecorp.armeria.internal.server.thrift;

import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.BINARY;
import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.COMPACT;
import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.TEXT;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.ClassRule;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.internal.server.thrift.ThriftDocServicePlugin.Entry;
import com.linecorp.armeria.internal.server.thrift.ThriftDocServicePlugin.EntryBuilder;
import com.linecorp.armeria.internal.testing.TestUtil;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.docs.DocServiceFilter;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.docs.ServiceSpecification;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

import testing.thrift.cassandra.Cassandra;
import testing.thrift.hbase.Hbase;
import testing.thrift.main.FooService;
import testing.thrift.main.HelloService;
import testing.thrift.main.HelloService.hello_args;
import testing.thrift.main.OnewayHelloService;
import testing.thrift.main.SleepService;

public class ThriftDocServiceTest {

    private static final HelloService.AsyncIface HELLO_SERVICE_HANDLER =
            (name, resultHandler) -> resultHandler.onComplete("Hello " + name);

    private static final SleepService.AsyncIface SLEEP_SERVICE_HANDLER =
            (duration, resultHandler) -> resultHandler.onComplete(duration);

    private static final hello_args EXAMPLE_HELLO = new hello_args("sample user");
    private static final HttpHeaders EXAMPLE_HEADERS_ALL = HttpHeaders.of(HttpHeaderNames.of("a"), "b");
    private static final HttpHeaders EXAMPLE_HEADERS_HELLO = HttpHeaders.of(HttpHeaderNames.of("c"), "d");
    private static final HttpHeaders EXAMPLE_HEADERS_FOO = HttpHeaders.of(HttpHeaderNames.of("e"), "f");
    private static final HttpHeaders EXAMPLE_HEADERS_FOO_BAR1 = HttpHeaders.of(HttpHeaderNames.of("g"), "h");

    private static final ObjectMapper mapper = new ObjectMapper();

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            if (TestUtil.isDocServiceDemoMode()) {
                sb.http(8080);
            }
            final THttpService helloAndSleepService =
                    THttpService.builder()
                                .addService("hello", HELLO_SERVICE_HANDLER)
                                .addService("sleep", SLEEP_SERVICE_HANDLER)
                                .build();
            final THttpService fooService =
                    THttpService.ofFormats(mock(FooService.AsyncIface.class), COMPACT);
            final THttpService cassandraService =
                    THttpService.ofFormats(mock(Cassandra.AsyncIface.class), BINARY);
            final THttpService cassandraServiceDebug =
                    THttpService.ofFormats(mock(Cassandra.AsyncIface.class), TEXT);
            final THttpService hbaseService =
                    THttpService.of(mock(Hbase.AsyncIface.class));
            final THttpService onewayHelloService =
                    THttpService.of(mock(OnewayHelloService.AsyncIface.class));

            sb.service("/", helloAndSleepService);
            sb.service("/foo", fooService);
            // Add a service with serviceUnder() to test whether prefix mapping is detected.
            sb.serviceUnder("/foo", fooService);
            sb.service("/cassandra", cassandraService);
            sb.service("/cassandra/debug", cassandraServiceDebug);
            sb.service("/hbase", hbaseService);
            sb.service("/oneway", onewayHelloService);

            sb.serviceUnder(
                    "/docs/",
                    DocService.builder()
                              .exampleHeaders(EXAMPLE_HEADERS_ALL)
                              .exampleHeaders(HelloService.class, EXAMPLE_HEADERS_HELLO)
                              .exampleHeaders(FooService.class, EXAMPLE_HEADERS_FOO)
                              .exampleHeaders(FooService.class, "bar1", EXAMPLE_HEADERS_FOO_BAR1)
                              .exampleRequests(ImmutableList.of(EXAMPLE_HELLO))
                              .build());
            sb.serviceUnder(
                    "/excludeAll/",
                    DocService.builder()
                              .exclude(DocServiceFilter.ofThrift())
                              .build());
        }
    };

    @Test
    public void testOk() throws Exception {
        if (TestUtil.isDocServiceDemoMode()) {
            Thread.sleep(Long.MAX_VALUE);
        }
        final Set<SerializationFormat> allThriftFormats = ThriftSerializationFormats.values();
        final List<Entry> entries = ImmutableList.of(
                new EntryBuilder(HelloService.class)
                        .endpoint(EndpointInfo.builder("*", "/")
                                              .fragment("hello")
                                              .defaultFormat(BINARY)
                                              .availableFormats(allThriftFormats)
                                              .build())
                        .build(),
                new EntryBuilder(SleepService.class)
                        .endpoint(EndpointInfo.builder("*", "/")
                                              .fragment("sleep")
                                              .defaultFormat(BINARY)
                                              .availableFormats(allThriftFormats)
                                              .build())
                        .build(),
                new EntryBuilder(FooService.class)
                        .endpoint(EndpointInfo.builder("*", "/foo").defaultFormat(COMPACT).build())
                        .endpoint(EndpointInfo.builder("*", "/foo/").defaultFormat(COMPACT).build())
                        .build(),
                new EntryBuilder(Cassandra.class)
                        .endpoint(EndpointInfo.builder("*", "/cassandra").defaultFormat(BINARY).build())
                        .endpoint(EndpointInfo.builder("*", "/cassandra/debug").defaultFormat(TEXT).build())
                        .build(),
                new EntryBuilder(Hbase.class)
                        .endpoint(EndpointInfo.builder("*", "/hbase")
                                              .defaultFormat(BINARY)
                                              .availableFormats(allThriftFormats)
                                              .build())
                        .build(),
                new EntryBuilder(OnewayHelloService.class)
                        .endpoint(EndpointInfo.builder("*", "/oneway")
                                              .defaultFormat(BINARY)
                                              .availableFormats(allThriftFormats)
                                              .build())
                        .build());

        final JsonNode expectedJson = mapper.valueToTree(
                new ThriftDocServicePlugin().generate(entries, (plugin, service, method) -> true,
                                                      new ThriftDescriptiveTypeInfoProvider()));

        // The specification generated by ThriftDocServicePlugin does not include the examples specified
        // when building a DocService, so we add them manually here.
        addExamples(expectedJson);

        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/docs/specification.json").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);

        final JsonNode actualJson = mapper.readTree(res.contentUtf8());
        // The specification generated by ThriftDocServicePlugin does not include the docstrings
        // because it's injected by the DocService, so we remove them here for easier comparison.
        removeDescriptionInfos(actualJson);
        removeDescriptionInfos(expectedJson);

        assertThatJson(actualJson).whenIgnoringPaths("docServiceRoute").isEqualTo(expectedJson);
    }

    private static void addExamples(JsonNode json) {
        // Add the global example.
        ((ArrayNode) json.get("exampleHeaders")).add(mapper.valueToTree(EXAMPLE_HEADERS_ALL));

        json.get("services").forEach(service -> {
            // Add the service-wide examples.
            final String serviceName = service.get("name").textValue();
            final ArrayNode serviceExampleHeaders = (ArrayNode) service.get("exampleHeaders");
            if (HelloService.class.getName().equals(serviceName)) {
                serviceExampleHeaders.add(mapper.valueToTree(EXAMPLE_HEADERS_HELLO));
            }
            if (FooService.class.getName().equals(serviceName)) {
                serviceExampleHeaders.add(mapper.valueToTree(EXAMPLE_HEADERS_FOO));
            }

            // Add the method-specific examples.
            service.get("methods").forEach(method -> {
                final String methodName = method.get("name").textValue();
                final ArrayNode exampleHeaders = (ArrayNode) method.get("exampleHeaders");
                if (FooService.class.getName().equals(serviceName) &&
                    "bar1".equals(methodName)) {
                    exampleHeaders.add(mapper.valueToTree(EXAMPLE_HEADERS_FOO_BAR1));
                }

                final ArrayNode exampleRequests = (ArrayNode) method.get("exampleRequests");
                if (HelloService.class.getName().equals(serviceName) &&
                    "hello".equals(methodName)) {
                    exampleRequests.add('{' + System.lineSeparator() +
                                        "  \"name\" : \"sample user\"" + System.lineSeparator() +
                                        '}');
                }
            });
        });
    }

    private static void removeDescriptionInfos(JsonNode json) {
        if (json.isObject()) {
            ((ObjectNode) json).remove("descriptionInfo");
        }

        if (json.isObject() || json.isArray()) {
            json.forEach(ThriftDocServiceTest::removeDescriptionInfos);
        }
    }

    @Test
    public void excludeAllServices() throws IOException {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/excludeAll/specification.json").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        final JsonNode actualJson = mapper.readTree(res.contentUtf8());
        final Route docServiceRoute = Route.builder().pathPrefix("/excludeAll").build();
        final JsonNode expectedJson = mapper.valueToTree(new ServiceSpecification(ImmutableList.of(),
                                                                                  ImmutableList.of(),
                                                                                  ImmutableList.of(),
                                                                                  ImmutableList.of(),
                                                                                  ImmutableList.of(),
                                                                                  docServiceRoute));
        assertThatJson(actualJson).isEqualTo(expectedJson);
    }

    @Test
    public void testMethodNotAllowed() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpPost req = new HttpPost(server.httpUri() + "/docs/specification.json");

            try (CloseableHttpResponse res = hc.execute(req)) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 405 Method Not Allowed");
            }
        }
    }
}
