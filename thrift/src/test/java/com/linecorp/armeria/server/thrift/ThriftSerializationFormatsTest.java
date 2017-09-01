/*
 * Copyright 2015 LINE Corporation
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

import static com.linecorp.armeria.common.MediaType.parse;
import static com.linecorp.armeria.common.SerializationFormat.find;
import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.BINARY;
import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.COMPACT;
import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.JSON;
import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.TEXT;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import com.linecorp.armeria.client.ClientOption;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.InvalidResponseException;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.testing.server.ServerRule;

/**
 * Test of serialization format validation / detection based on HTTP headers.
 */
public class ThriftSerializationFormatsTest {

    private static final HelloService.Iface HELLO_SERVICE = name -> "Hello, " + name + '!';

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/hello", THttpService.of(HELLO_SERVICE))
              .service("/hellobinaryonly", THttpService.ofFormats(HELLO_SERVICE, BINARY))
              .service("/hellotextonly", THttpService.ofFormats(HELLO_SERVICE, TEXT));
        }
    };

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void findByMediaType() {
        // The 'protocol' parameter has to be case-insensitive.
        assertThat(find(parse("application/x-thrift; protocol=tbinary"))).containsSame(BINARY);
        assertThat(find(parse("application/x-thrift;protocol=TCompact"))).containsSame(COMPACT);
        assertThat(find(parse("application/x-thrift ; protocol=\"TjSoN\""))).containsSame(JSON);

        // An unknown parameter ('version' in this case) should not be accepted.
        assertThat(find(parse("application/x-thrift ; version=3;protocol=ttext"))).isEmpty();

        // 'charset=utf-8' parameter should be accepted for TJSON and TTEXT.
        assertThat(find(parse("application/x-thrift; protocol=tjson; charset=utf-8"))).containsSame(JSON);
        assertThat(find(parse("application/vnd.apache.thrift.json; charset=utf-8"))).containsSame(JSON);
        assertThat(find(parse("application/x-thrift; protocol=ttext; charset=utf-8"))).containsSame(TEXT);
        assertThat(find(parse("application/vnd.apache.thrift.text; charset=utf-8"))).containsSame(TEXT);

        // .. but neither non-UTF-8 charsets:
        assertThat(find(parse("application/x-thrift; protocol=tjson; charset=us-ascii"))).isEmpty();
        assertThat(find(parse("application/vnd.apache.thrift.json; charset=us-ascii"))).isEmpty();
        assertThat(find(parse("application/x-thrift; protocol=ttext; charset=us-ascii"))).isEmpty();
        assertThat(find(parse("application/vnd.apache.thrift.text; charset=us-ascii"))).isEmpty();

        // .. nor binary/compact formats:
        assertThat(find(parse("application/x-thrift; protocol=tbinary; charset=utf-8"))).isEmpty();
        assertThat(find(parse("application/vnd.apache.thrift.binary; charset=utf-8"))).isEmpty();
        assertThat(find(parse("application/x-thrift; protocol=tcompact; charset=utf-8"))).isEmpty();
        assertThat(find(parse("application/vnd.apache.thrift.compact; charset=utf-8"))).isEmpty();
    }

    @Test
    @SuppressWarnings("deprecation")
    public void backwardCompatibility() {
        assertThat(SerializationFormat.ofThrift()).containsExactlyInAnyOrder(BINARY, COMPACT, JSON, TEXT);
        assertThat(SerializationFormat.THRIFT_BINARY).isNotNull();
        assertThat(SerializationFormat.THRIFT_COMPACT).isNotNull();
        assertThat(SerializationFormat.THRIFT_JSON).isNotNull();
        assertThat(SerializationFormat.THRIFT_TEXT).isNotNull();
    }

    @Test
    public void defaults() throws Exception {
        HelloService.Iface client = Clients.newClient(server.uri(BINARY, "/hello"), HelloService.Iface.class);
        assertThat(client.hello("Trustin")).isEqualTo("Hello, Trustin!");
    }

    @Test
    public void notDefault() throws Exception {
        HelloService.Iface client = Clients.newClient(server.uri(TEXT, "/hello"), HelloService.Iface.class);
        assertThat(client.hello("Trustin")).isEqualTo("Hello, Trustin!");
    }

    @Test
    public void notAllowed() throws Exception {
        HelloService.Iface client =
                Clients.newClient(server.uri(TEXT, "/hellobinaryonly"), HelloService.Iface.class);
        thrown.expect(InvalidResponseException.class);
        thrown.expectMessage("415 Unsupported Media Type");
        client.hello("Trustin");
    }

    @Test
    public void contentTypeNotThrift() throws Exception {
        // Browser clients often send a non-thrift content type.
        HttpHeaders headers = HttpHeaders.of(HttpHeaderNames.CONTENT_TYPE,
                                             "text/plain; charset=utf-8");
        HelloService.Iface client =
                Clients.newClient(server.uri(BINARY, "/hello"),
                                  HelloService.Iface.class,
                                  ClientOption.HTTP_HEADERS.newValue(headers));
        assertThat(client.hello("Trustin")).isEqualTo("Hello, Trustin!");
    }

    @Test
    public void acceptNotSameAsContentType() throws Exception {
        HttpHeaders headers = HttpHeaders.of(HttpHeaderNames.ACCEPT,
                                             "application/x-thrift; protocol=TBINARY");
        HelloService.Iface client =
                Clients.newClient(server.uri(TEXT, "/hello"),
                                  HelloService.Iface.class,
                                  ClientOption.HTTP_HEADERS.newValue(headers));
        thrown.expect(InvalidResponseException.class);
        thrown.expectMessage("406 Not Acceptable");
        client.hello("Trustin");
    }

    @Test
    public void defaultSerializationFormat() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            // Send a TTEXT request with content type 'application/x-thrift' without 'protocol' parameter.
            HttpPost req = new HttpPost(server.uri("/hellotextonly"));
            req.setHeader("Content-type", "application/x-thrift");
            req.setEntity(new StringEntity(
                    '{' +
                    "  \"method\": \"hello\"," +
                    "  \"type\":\"CALL\"," +
                    "  \"args\": { \"name\": \"trustin\"}" +
                    '}', StandardCharsets.UTF_8));

            try (CloseableHttpResponse res = hc.execute(req)) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
            }
        }
    }
}
