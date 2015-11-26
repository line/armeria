/*
 * Copyright 2015 LINE Corporation
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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Optional;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;
import com.linecorp.armeria.server.AbstractServerTest;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.VirtualHostBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.server.thrift.ThriftService;
import com.linecorp.armeria.service.test.thrift.cassandra.Cassandra;
import com.linecorp.armeria.service.test.thrift.hbase.Hbase;
import com.linecorp.armeria.service.test.thrift.main.FooService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;

public class DocServiceTest extends AbstractServerTest {

    private static final HelloService.AsyncIface HELLO_SERVICE_HANDLER =
            (name, resultHandler) -> resultHandler.onComplete("Hello " + name);

    @Override
    protected void configureServer(ServerBuilder sb) {
        final ThriftService helloService = new ThriftService(HELLO_SERVICE_HANDLER);
        final ThriftService fooService = new ThriftService(mock(FooService.AsyncIface.class));
        final ThriftService cassandraService = new ThriftService(mock(Cassandra.AsyncIface.class));
        final ThriftService hbaseService = new ThriftService(mock(Hbase.AsyncIface.class));

        final VirtualHostBuilder defaultVirtualHost = new VirtualHostBuilder();

        defaultVirtualHost.serviceAt("/hello", helloService);
        defaultVirtualHost.serviceAt("/internal/debug/hello", new ThriftService(HELLO_SERVICE_HANDLER,
                                                                                ThriftProtocolFactories.TEXT));
        defaultVirtualHost.serviceAt("/foo", fooService);
        defaultVirtualHost.serviceAt("/cassandra", cassandraService);
        defaultVirtualHost.serviceAt("/hbase", hbaseService);

        defaultVirtualHost.serviceUnder("/docs/", new DocService().decorate(LoggingService::new));

        sb.defaultVirtualHost(defaultVirtualHost.build());
    }

    @Test
    public void testOk() throws Exception {
        HashMap<Class<?>, Optional<String>> serviceMap = new LinkedHashMap<>();
        serviceMap.put(HelloService.class, Optional.of("/internal/debug/hello"));
        serviceMap.put(FooService.class, Optional.empty());
        serviceMap.put(Cassandra.class, Optional.empty());
        serviceMap.put(Hbase.class, Optional.empty());
        final String expected = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(
                Specification.forServiceClasses(serviceMap));

        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpGet req = new HttpGet(specificationUri());

            try (CloseableHttpResponse res = hc.execute(req)) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is(expected));
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
