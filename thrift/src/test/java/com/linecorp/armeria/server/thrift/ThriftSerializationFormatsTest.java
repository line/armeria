package com.linecorp.armeria.server.thrift;

import static com.linecorp.armeria.common.MediaType.parse;
import static com.linecorp.armeria.common.SerializationFormat.find;
import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.BINARY;
import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.COMPACT;
import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.JSON;
import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.TEXT;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import com.linecorp.armeria.client.ClientOption;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.InvalidResponseException;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.test.AbstractServerTest;

/**
 * Test of serialization format validation / detection based on HTTP headers.
 */
public class ThriftSerializationFormatsTest extends AbstractServerTest {

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    private static final HelloService.Iface HELLO_SERVICE = name -> "Hello, " + name + '!';

    @Override
    protected void configureServer(ServerBuilder sb) {
        sb.serviceAt("/hello", THttpService.of(HELLO_SERVICE))
          .serviceAt("/hellobinaryonly", THttpService.ofFormats(HELLO_SERVICE, BINARY));
    }

    @Test
    public void findByMediaType() {
        // The 'protocol' parameter has to be case-insensitive.
        assertThat(find(parse("application/x-thrift; protocol=tbinary"))).containsSame(BINARY);
        assertThat(find(parse("application/x-thrift;protocol=TCompact"))).containsSame(COMPACT);
        assertThat(find(parse("application/x-thrift ; protocol=\"TjSoN\""))).containsSame(JSON);
        assertThat(find(parse("application/x-thrift ; version=3;protocol=ttext"))).containsSame(TEXT);
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
        HelloService.Iface client = Clients.newClient("tbinary+" + uri("/hello"), HelloService.Iface.class);
        assertThat(client.hello("Trustin")).isEqualTo("Hello, Trustin!");
    }

    @Test
    public void notDefault() throws Exception {
        HelloService.Iface client = Clients.newClient("ttext+" + uri("/hello"), HelloService.Iface.class);
        assertThat(client.hello("Trustin")).isEqualTo("Hello, Trustin!");
    }

    @Test
    public void notAllowed() throws Exception {
        HelloService.Iface client =
                Clients.newClient("ttext+" + uri("/hellobinaryonly"), HelloService.Iface.class);
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
                Clients.newClient("tbinary+" + uri("/hello"),
                                  HelloService.Iface.class,
                                  ClientOption.HTTP_HEADERS.newValue(headers));
        assertThat(client.hello("Trustin")).isEqualTo("Hello, Trustin!");
    }

    @Test
    public void acceptNotSameAsContentType() throws Exception {
        HttpHeaders headers = HttpHeaders.of(HttpHeaderNames.ACCEPT,
                                             "application/x-thrift; protocol=TBINARY");
        HelloService.Iface client =
                Clients.newClient("ttext+" + uri("/hello"),
                                  HelloService.Iface.class,
                                  ClientOption.HTTP_HEADERS.newValue(headers));
        thrown.expect(InvalidResponseException.class);
        thrown.expectMessage("406 Not Acceptable");
        client.hello("Trustin");
    }
}
