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
package com.linecorp.armeria.server.docs;

import static com.linecorp.armeria.common.SerializationFormat.THRIFT_BINARY;
import static com.linecorp.armeria.common.SerializationFormat.THRIFT_COMPACT;
import static com.linecorp.armeria.common.SerializationFormat.THRIFT_TEXT;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.server.AbstractServerTest;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.cassandra.Cassandra;
import com.linecorp.armeria.service.test.thrift.hbase.Hbase;
import com.linecorp.armeria.service.test.thrift.main.FooService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.service.test.thrift.main.HelloService.hello_args;

public class DocServiceTest extends AbstractServerTest {

    private static final HelloService.AsyncIface HELLO_SERVICE_HANDLER =
            (name, resultHandler) -> resultHandler.onComplete("Hello " + name);

    private static final hello_args SAMPLE_HELLO = new hello_args().setName("sample user");
    private static final Map<Class<?>, Map<String, String>> SAMPLE_HTTP_HEADERS = ImmutableMap.of(
            HelloService.class, ImmutableMap.of("foobar", "barbaz"),
            FooService.class, ImmutableMap.of("barbaz", "barbar"));

    @Override
    protected void configureServer(ServerBuilder sb) {
        final THttpService helloService = THttpService.of(HELLO_SERVICE_HANDLER);
        final THttpService fooService = THttpService.ofFormats(mock(FooService.AsyncIface.class),
                                                               THRIFT_COMPACT);
        final THttpService cassandraService = THttpService.ofFormats(mock(Cassandra.AsyncIface.class),
                                                                     THRIFT_BINARY);
        final THttpService cassandraServiceDebug =
                THttpService.ofFormats(mock(Cassandra.AsyncIface.class), THRIFT_TEXT);
        final THttpService hbaseService = THttpService.of(mock(Hbase.AsyncIface.class));

        sb.serviceAt("/hello", helloService);
        sb.serviceAt("/foo", fooService);
        sb.serviceAt("/cassandra", cassandraService);
        sb.serviceAt("/cassandra/debug", cassandraServiceDebug);
        sb.serviceAt("/hbase", hbaseService);

        sb.serviceUnder("/docs/",
                        new DocService(ImmutableList.of(SAMPLE_HELLO), SAMPLE_HTTP_HEADERS)
                                .decorate(LoggingService::new));
    }

    @Test
    public void testOk() throws Exception {
        final Map<Class<?>, Iterable<EndpointInfo>> serviceMap = new LinkedHashMap<>();
        serviceMap.put(HelloService.class, Collections.singletonList(
                EndpointInfo.of("*", "/hello", THRIFT_BINARY, SerializationFormat.ofThrift())));
        serviceMap.put(FooService.class, Collections.singletonList(
                EndpointInfo.of("*", "/foo", THRIFT_COMPACT, EnumSet.of(THRIFT_COMPACT))));
        serviceMap.put(Cassandra.class, Arrays.asList(
                EndpointInfo.of("*", "/cassandra", THRIFT_BINARY, EnumSet.of(THRIFT_BINARY)),
                EndpointInfo.of("*", "/cassandra/debug", THRIFT_TEXT, EnumSet.of(THRIFT_TEXT))));
        serviceMap.put(Hbase.class, Collections.singletonList(
                EndpointInfo.of("*", "/hbase", THRIFT_BINARY, SerializationFormat.ofThrift())));

        final String expected = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(
                Specification.forServiceClasses(serviceMap,
                                                ImmutableMap.of(hello_args.class, SAMPLE_HELLO),
                                                SAMPLE_HTTP_HEADERS));

        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpGet req = new HttpGet(specificationUri());

            try (CloseableHttpResponse res = hc.execute(req)) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                String responseJson = EntityUtils.toString(res.getEntity());
                assertThat(responseJson, is(expected));
            }
        }
    }

    @Test
    public void testMethodNotAllowed() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpPost req = new HttpPost(specificationUri());

            try (CloseableHttpResponse res = hc.execute(req)) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 405 Method Not Allowed"));
            }
        }
    }

    private static String specificationUri() {
        return uri("/docs/specification.json");
    }
}
