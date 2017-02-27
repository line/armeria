/*
 * Copyright 2016 LINE Corporation
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
package com.linecorp.armeria.server.thrift;

import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.BINARY;
import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.COMPACT;
import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.TEXT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.server.thrift.ThriftServiceSpecificationGenerator.Entry;
import com.linecorp.armeria.server.thrift.ThriftServiceSpecificationGenerator.EntryBuilder;
import com.linecorp.armeria.service.test.thrift.cassandra.Cassandra;
import com.linecorp.armeria.service.test.thrift.hbase.Hbase;
import com.linecorp.armeria.service.test.thrift.main.FooService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.service.test.thrift.main.OnewayHelloService;
import com.linecorp.armeria.service.test.thrift.main.SleepService;
import com.linecorp.armeria.test.AbstractServerTest;

import io.netty.util.AsciiString;

public class ThriftDocServiceTest extends AbstractServerTest {

    private static final HelloService.AsyncIface HELLO_SERVICE_HANDLER =
            (name, resultHandler) -> resultHandler.onComplete("Hello " + name);

    private static final SleepService.AsyncIface SLEEP_SERVICE_HANDLER =
            (duration, resultHandler) -> resultHandler.onComplete(duration);

    private static final ListMultimap<String, HttpHeaders> EXAMPLE_HTTP_HEADERS = ImmutableListMultimap.of(
            HelloService.class.getName(), HttpHeaders.of(AsciiString.of("foobar"), "barbaz"),
            FooService.class.getName(), HttpHeaders.of(AsciiString.of("barbaz"), "barbar"));

    @Override
    protected void configureServer(ServerBuilder sb) {
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

        sb.serviceAt("/", helloAndSleepService);
        sb.serviceAt("/foo", fooService);
        sb.serviceAt("/cassandra", cassandraService);
        sb.serviceAt("/cassandra/debug", cassandraServiceDebug);
        sb.serviceAt("/hbase", hbaseService);
        sb.serviceAt("/oneway", onewayHelloService);

        sb.serviceUnder("/docs/", new DocService(EXAMPLE_HTTP_HEADERS.asMap()).decorate(LoggingService::new));
        // FIXME(trustin): Bring the example requests back.
    }

    @Test
    public void testOk() throws Exception {
        final Set<SerializationFormat> allThriftFormats = ThriftSerializationFormats.values();
        final List<Entry> entries = ImmutableList.of(
                new EntryBuilder(HelloService.class)
                        .endpoint(new EndpointInfo("*", "/", "hello", BINARY, allThriftFormats))
                        .exampleHttpHeaders(EXAMPLE_HTTP_HEADERS.get(HelloService.class.getName()))
                        .build(),
                new EntryBuilder(SleepService.class)
                        .endpoint(new EndpointInfo("*", "/", "sleep", BINARY, allThriftFormats))
                        .exampleHttpHeaders(EXAMPLE_HTTP_HEADERS.get(SleepService.class.getName()))
                        .build(),
                new EntryBuilder(FooService.class)
                        .endpoint(new EndpointInfo("*", "/foo", "", COMPACT, ImmutableSet.of(COMPACT)))
                        .exampleHttpHeaders(EXAMPLE_HTTP_HEADERS.get(FooService.class.getName()))
                        .build(),
                new EntryBuilder(Cassandra.class)
                        .endpoint(new EndpointInfo("*", "/cassandra", "", BINARY, ImmutableSet.of(BINARY)))
                        .endpoint(new EndpointInfo("*", "/cassandra/debug", "", TEXT, ImmutableSet.of(TEXT)))
                        .exampleHttpHeaders(EXAMPLE_HTTP_HEADERS.get(Cassandra.class.getName()))
                        .build(),
                new EntryBuilder(Hbase.class)
                        .endpoint(new EndpointInfo("*", "/hbase", "", BINARY, allThriftFormats))
                        .exampleHttpHeaders(EXAMPLE_HTTP_HEADERS.get(Hbase.class.getName()))
                        .build(),
                new EntryBuilder(OnewayHelloService.class)
                        .endpoint(new EndpointInfo("*", "/oneway", "", BINARY, allThriftFormats))
                        .exampleHttpHeaders(EXAMPLE_HTTP_HEADERS.get(
                                OnewayHelloService.class.getName()))
                        .build());

        final ObjectMapper mapper = new ObjectMapper();
        final String expectedJson = mapper.writeValueAsString(
                ThriftServiceSpecificationGenerator.generate(entries));
        // FIXME(trustin): Bring this back.
        //ImmutableMap.of(hello_args.class, SAMPLE_HELLO)

        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpGet req = new HttpGet(specificationUri());

            try (CloseableHttpResponse res = hc.execute(req)) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
                String responseJson = EntityUtils.toString(res.getEntity());

                // Convert to Map for order-insensitive comparison.
                Map<?, ?> actual = mapper.readValue(responseJson, Map.class);
                Map<?, ?> expected = mapper.readValue(expectedJson, Map.class);
                assertThat(actual).isEqualTo(expected);
            }
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
        return uri("/docs/specification.json");
    }
}
