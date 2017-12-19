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
package com.linecorp.armeria.server.thrift;

import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.BINARY;
import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.COMPACT;
import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.TEXT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Set;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.ClassRule;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.docs.DocServiceBuilder;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.thrift.ThriftDocServicePlugin.Entry;
import com.linecorp.armeria.server.thrift.ThriftDocServicePlugin.EntryBuilder;
import com.linecorp.armeria.service.test.thrift.cassandra.Cassandra;
import com.linecorp.armeria.service.test.thrift.hbase.Hbase;
import com.linecorp.armeria.service.test.thrift.main.FooService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.service.test.thrift.main.HelloService.hello_args;
import com.linecorp.armeria.service.test.thrift.main.OnewayHelloService;
import com.linecorp.armeria.service.test.thrift.main.SleepService;
import com.linecorp.armeria.testing.server.ServerRule;

import io.netty.util.AsciiString;

public class ThriftDocServiceTest {

    private static final HelloService.AsyncIface HELLO_SERVICE_HANDLER =
            (name, resultHandler) -> resultHandler.onComplete("Hello " + name);

    private static final SleepService.AsyncIface SLEEP_SERVICE_HANDLER =
            (duration, resultHandler) -> resultHandler.onComplete(duration);

    private static final hello_args EXAMPLE_HELLO = new hello_args("sample user");
    private static final HttpHeaders EXAMPLE_HEADERS_ALL = HttpHeaders.of(AsciiString.of("a"), "b");
    private static final HttpHeaders EXAMPLE_HEADERS_HELLO = HttpHeaders.of(AsciiString.of("c"), "d");
    private static final HttpHeaders EXAMPLE_HEADERS_FOO = HttpHeaders.of(AsciiString.of("e"), "f");
    private static final HttpHeaders EXAMPLE_HEADERS_FOO_BAR1 = HttpHeaders.of(AsciiString.of("g"), "h");

    private static final ObjectMapper mapper = new ObjectMapper();

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final THttpService helloAndSleepService = THttpService.of(ImmutableMap.of(
                    "hello", HELLO_SERVICE_HANDLER,
                    "sleep", SLEEP_SERVICE_HANDLER));
            final THttpService fooService = THttpService.ofFormats(mock(FooService.AsyncIface.class),
                                                                   COMPACT);
            final THttpService cassandraService = THttpService.ofFormats(mock(Cassandra.AsyncIface.class),
                                                                         BINARY);
            final THttpService cassandraServiceDebug =
                    THttpService.ofFormats(mock(Cassandra.AsyncIface.class), TEXT);
            final THttpService hbaseService = THttpService.of(mock(Hbase.AsyncIface.class));
            final THttpService onewayHelloService = THttpService.of(mock(OnewayHelloService.AsyncIface.class));

            sb.service("/", helloAndSleepService);
            sb.service("/foo", fooService);
            sb.service("/cassandra", cassandraService);
            sb.service("/cassandra/debug", cassandraServiceDebug);
            sb.service("/hbase", hbaseService);
            sb.service("/oneway", onewayHelloService);

            sb.serviceUnder(
                    "/docs/",
                    new DocServiceBuilder()
                            .exampleHttpHeaders(EXAMPLE_HEADERS_ALL)
                            .exampleHttpHeaders(HelloService.class, EXAMPLE_HEADERS_HELLO)
                            .exampleHttpHeaders(FooService.class, EXAMPLE_HEADERS_FOO)
                            .exampleHttpHeaders(FooService.class, "bar1", EXAMPLE_HEADERS_FOO_BAR1)
                            .exampleRequest(EXAMPLE_HELLO)
                            .build());
        }
    };

    @Test
    public void testOk() throws Exception {
        final Set<SerializationFormat> allThriftFormats = ThriftSerializationFormats.values();
        final List<Entry> entries = ImmutableList.of(
                new EntryBuilder(HelloService.class)
                        .endpoint(new EndpointInfo("*", "/", "hello", BINARY, allThriftFormats))
                        .build(),
                new EntryBuilder(SleepService.class)
                        .endpoint(new EndpointInfo("*", "/", "sleep", BINARY, allThriftFormats))
                        .build(),
                new EntryBuilder(FooService.class)
                        .endpoint(new EndpointInfo("*", "/foo", "", COMPACT, ImmutableSet.of(COMPACT)))
                        .build(),
                new EntryBuilder(Cassandra.class)
                        .endpoint(new EndpointInfo("*", "/cassandra", "", BINARY, ImmutableSet.of(BINARY)))
                        .endpoint(new EndpointInfo("*", "/cassandra/debug", "", TEXT, ImmutableSet.of(TEXT)))
                        .build(),
                new EntryBuilder(Hbase.class)
                        .endpoint(new EndpointInfo("*", "/hbase", "", BINARY, allThriftFormats))
                        .build(),
                new EntryBuilder(OnewayHelloService.class)
                        .endpoint(new EndpointInfo("*", "/oneway", "", BINARY, allThriftFormats))
                        .build());

        final JsonNode expectedJson = mapper.valueToTree(ThriftDocServicePlugin.generate(entries));

        // The specification generated by ThriftDocServicePlugin does not include the examples specified
        // when building a DocService, so we add them manually here.
        addExamples(expectedJson);

        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpGet req = new HttpGet(specificationUri());

            try (CloseableHttpResponse res = hc.execute(req)) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
                final JsonNode actualJson = mapper.readTree(EntityUtils.toString(res.getEntity()));

                // The specification generated by ThriftDocServicePlugin does not include the docstrings
                // because it's injected by the DocService, so we remove them here for easier comparison.
                removeDocStrings(actualJson);

                // Convert to the prettified strings for human-readable comparison.
                final ObjectWriter writer = mapper.writerWithDefaultPrettyPrinter();
                final String actualJsonString = writer.writeValueAsString(actualJson);
                final String expectedJsonString = writer.writeValueAsString(expectedJson);
                assertThat(actualJsonString).isEqualTo(expectedJsonString);
            }
        }
    }

    private static void addExamples(JsonNode json) {
        // Add the global example.
        ((ArrayNode) json.get("exampleHttpHeaders")).add(mapper.valueToTree(EXAMPLE_HEADERS_ALL));

        json.get("services").forEach(service -> {
            // Add the service-wide examples.
            final String serviceName = service.get("name").textValue();
            final ArrayNode serviceExampleHttpHeaders = (ArrayNode) service.get("exampleHttpHeaders");
            if (HelloService.class.getName().equals(serviceName)) {
                serviceExampleHttpHeaders.add(mapper.valueToTree(EXAMPLE_HEADERS_HELLO));
            }
            if (FooService.class.getName().equals(serviceName)) {
                serviceExampleHttpHeaders.add(mapper.valueToTree(EXAMPLE_HEADERS_FOO));
            }

            // Add the method-specific examples.
            service.get("methods").forEach(method -> {
                final String methodName = method.get("name").textValue();
                final ArrayNode exampleHttpHeaders = (ArrayNode) method.get("exampleHttpHeaders");
                if (FooService.class.getName().equals(serviceName) &&
                    "bar1".equals(methodName)) {
                    exampleHttpHeaders.add(mapper.valueToTree(EXAMPLE_HEADERS_FOO_BAR1));
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

    private static void removeDocStrings(JsonNode json) {
        if (json.isObject()) {
            ((ObjectNode) json).remove("docString");
        }

        if (json.isObject() || json.isArray()) {
            json.forEach(ThriftDocServiceTest::removeDocStrings);
        }
    }

    @Test
    public void testMethodNotAllowed() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpPost req = new HttpPost(specificationUri());

            try (CloseableHttpResponse res = hc.execute(req)) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 405 Method Not Allowed");
            }
        }
    }

    private static String specificationUri() {
        return server.uri("/docs/specification.json");
    }
}
