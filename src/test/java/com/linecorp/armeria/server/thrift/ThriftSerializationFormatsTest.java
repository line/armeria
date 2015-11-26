package com.linecorp.armeria.server.thrift;

import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import com.linecorp.armeria.client.ClientOption;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.InvalidResponseException;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.server.AbstractServerTest;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.VirtualHostBuilder;
import com.linecorp.armeria.service.test.thrift.main.HelloService;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;

/**
 * Test of serialization format validation / detection based on HTTP headers.
 */
public class ThriftSerializationFormatsTest extends AbstractServerTest {

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    private static final HelloService.Iface HELLO_SERVICE = name -> "Hello, " + name + '!';

    @Override
    protected void configureServer(ServerBuilder sb) {
        sb.defaultVirtualHost(new VirtualHostBuilder()
                                      .serviceAt("/hello", ThriftService.of(HELLO_SERVICE))
                                      .serviceAt("/hellobinaryonly",
                                                 ThriftService.ofFormats(HELLO_SERVICE,
                                                                         SerializationFormat.THRIFT_BINARY))
                                      .build());
    }

    @Test
    public void defaults() throws Exception {
        HelloService.Iface client =
                Clients.newClient("tbinary+" + uri("/hello"), HelloService.Iface.class);
        String res = client.hello("Trustin");
        assertEquals("Hello, Trustin!", res);
    }

    @Test
    public void notDefault() throws Exception {
        HelloService.Iface client =
                Clients.newClient("ttext+" + uri("/hello"), HelloService.Iface.class);
        String res = client.hello("Trustin");
        assertEquals("Hello, Trustin!", res);
    }

    @Test
    public void notAllowed() throws Exception {
        HelloService.Iface client =
                Clients.newClient("ttext+" + uri("/hellobinaryonly"), HelloService.Iface.class);
        thrown.expect(InvalidResponseException.class);
        thrown.expectMessage("HTTP Response code: 415 Unsupported Media Type");
        client.hello("Trustin");
    }

    @Test
    public void contentTypeNotThrift() throws Exception {
        HttpHeaders headers = new DefaultHttpHeaders().set(HttpHeaderNames.CONTENT_TYPE,
                                                           "text/html; protocol=TBINARY");
        HelloService.Iface client =
                Clients.newClient("ttext+" + uri("/hello"),
                                  HelloService.Iface.class,
                                  ClientOption.HTTP_HEADERS.newValue(headers));
        thrown.expect(InvalidResponseException.class);
        thrown.expectMessage("HTTP Response code: 415 Unsupported Media Type");
        client.hello("Trustin");
    }

    @Test
    public void acceptNotThrift() throws Exception {
        HttpHeaders headers = new DefaultHttpHeaders().set(HttpHeaderNames.ACCEPT,
                                                           "text/html; protocol=TBINARY");
        HelloService.Iface client =
                Clients.newClient("ttext+" + uri("/hello"),
                                  HelloService.Iface.class,
                                  ClientOption.HTTP_HEADERS.newValue(headers));
        thrown.expect(InvalidResponseException.class);
        thrown.expectMessage("HTTP Response code: 415 Unsupported Media Type");
        client.hello("Trustin");
    }

    @Test
    public void acceptNotSameAsContentType() throws Exception {
        HttpHeaders headers = new DefaultHttpHeaders().set(HttpHeaderNames.ACCEPT,
                                                           "application/x-thrift; protocol=TBINARY");
        HelloService.Iface client =
                Clients.newClient("ttext+" + uri("/hello"),
                                  HelloService.Iface.class,
                                  ClientOption.HTTP_HEADERS.newValue(headers));
        thrown.expect(InvalidResponseException.class);
        thrown.expectMessage("HTTP Response code: 406 Not Acceptable");
        client.hello("Trustin");
    }
}
