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
import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.TEXT_NAMED_ENUM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.InvalidResponseHeadersException;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

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

    @Test
    public void findByMediaType() {
        // The 'protocol' parameter has to be case-insensitive.
        assertThat(find(parse("application/x-thrift; protocol=tbinary"))).isSameAs(BINARY);
        assertThat(find(parse("application/x-thrift;protocol=TCompact"))).isSameAs(COMPACT);
        assertThat(find(parse("application/x-thrift ; protocol=\"TjSoN\""))).isSameAs(JSON);

        // An unknown parameter ('version' in this case) should not be accepted.
        assertThat(find(parse("application/x-thrift ; version=3;protocol=ttext"))).isNull();

        // 'charset=utf-8' parameter should be accepted for TJSON and TTEXT.
        assertThat(find(parse("application/x-thrift; protocol=tjson; charset=utf-8"))).isSameAs(JSON);
        assertThat(find(parse("application/vnd.apache.thrift.json; charset=utf-8"))).isSameAs(JSON);
        assertThat(find(parse("application/x-thrift; protocol=ttext; charset=utf-8"))).isSameAs(TEXT);
        assertThat(find(parse("application/vnd.apache.thrift.text; charset=utf-8"))).isSameAs(TEXT);
        assertThat(find(parse("application/x-thrift; protocol=ttext_named_enum; charset=utf-8")))
                .isSameAs(TEXT_NAMED_ENUM);
        assertThat(find(parse("application/vnd.apache.thrift.text_named_enum; charset=utf-8")))
                .isSameAs(TEXT_NAMED_ENUM);

        // .. but neither non-UTF-8 charsets:
        assertThat(find(parse("application/x-thrift; protocol=tjson; charset=us-ascii"))).isNull();
        assertThat(find(parse("application/vnd.apache.thrift.json; charset=us-ascii"))).isNull();
        assertThat(find(parse("application/x-thrift; protocol=ttext; charset=us-ascii"))).isNull();
        assertThat(find(parse("application/vnd.apache.thrift.text; charset=us-ascii"))).isNull();
        assertThat(find(parse("application/x-thrift; protocol=ttext_named_enum; charset=us-ascii"))).isNull();
        assertThat(find(parse("application/vnd.apache.thrift.text_named_enum; charset=us-ascii"))).isNull();

        // .. nor binary/compact formats:
        assertThat(find(parse("application/x-thrift; protocol=tbinary; charset=utf-8"))).isNull();
        assertThat(find(parse("application/vnd.apache.thrift.binary; charset=utf-8"))).isNull();
        assertThat(find(parse("application/x-thrift; protocol=tcompact; charset=utf-8"))).isNull();
        assertThat(find(parse("application/vnd.apache.thrift.compact; charset=utf-8"))).isNull();
    }

    @Test
    public void defaults() throws Exception {
        final HelloService.Iface client = Clients.newClient(server.httpUri(BINARY) + "/hello",
                                                            HelloService.Iface.class);
        assertThat(client.hello("Trustin")).isEqualTo("Hello, Trustin!");
    }

    @Test
    public void notDefault() throws Exception {
        final HelloService.Iface client = Clients.newClient(server.httpUri(TEXT) + "/hello",
                                                            HelloService.Iface.class);
        assertThat(client.hello("Trustin")).isEqualTo("Hello, Trustin!");
    }

    @Test
    public void notAllowed() throws Exception {
        final HelloService.Iface client =
                Clients.newClient(server.httpUri(TEXT) + "/hellobinaryonly", HelloService.Iface.class);
        assertThatThrownBy(() -> client.hello("Trustin")).isInstanceOf(InvalidResponseHeadersException.class)
                                                         .hasMessageContaining(":status=415");
    }

    @Test
    public void contentTypeNotThrift() throws Exception {
        // Browser clients often send a non-Thrift content type.
        final HelloService.Iface client =
                Clients.builder(server.httpUri(BINARY) + "/hello")
                       .setHeader(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8")
                       .build(HelloService.Iface.class);
        assertThat(client.hello("Trustin")).isEqualTo("Hello, Trustin!");
    }

    @Test
    public void acceptNotSameAsContentType() throws Exception {
        final HelloService.Iface client =
                Clients.builder(server.httpUri(TEXT) + "/hello")
                       .setHeader(HttpHeaderNames.ACCEPT, "application/x-thrift; protocol=TBINARY")
                       .build(HelloService.Iface.class);
        assertThatThrownBy(() -> client.hello("Trustin")).isInstanceOf(InvalidResponseHeadersException.class)
                                                         .hasMessageContaining(":status=406");
    }

    @Test
    public void defaultSerializationFormat() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            // Send a TTEXT request with content type 'application/x-thrift' without 'protocol' parameter.
            final HttpPost req = new HttpPost(server.httpUri() + "/hellotextonly");
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

    @Test
    public void givenClients_whenBinary_thenBuildClient() throws Exception {
        HelloService.Iface client =
                Clients.newClient(Scheme.of(BINARY, SessionProtocol.HTTP), server.httpEndpoint(), "/hello",
                                  HelloService.Iface.class);
        assertThat(client.hello("Trustin")).isEqualTo("Hello, Trustin!");

        client = Clients.newClient(Scheme.of(TEXT, SessionProtocol.HTTP), server.httpEndpoint(), "/hello",
                                   HelloService.Iface.class);
        assertThat(client.hello("Trustin")).isEqualTo("Hello, Trustin!");
    }
}
