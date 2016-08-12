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

package com.linecorp.armeria.client.thrift;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isOneOf;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;

import javax.net.ssl.TrustManagerFactory;

import org.apache.thrift.async.AsyncMethodCallback;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import com.linecorp.armeria.client.ClientDecoration;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOption;
import com.linecorp.armeria.client.ClientOptionValue;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.SessionOption;
import com.linecorp.armeria.client.SessionOptionValue;
import com.linecorp.armeria.client.SessionOptions;
import com.linecorp.armeria.client.http.HttpClientFactory;
import com.linecorp.armeria.client.logging.KeyedChannelPoolLoggingHandler;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.client.pool.KeyedChannelPoolHandler;
import com.linecorp.armeria.client.pool.PoolKey;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.common.thrift.ThriftCall;
import com.linecorp.armeria.common.thrift.ThriftReply;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.main.DevNullService;
import com.linecorp.armeria.service.test.thrift.main.FileService;
import com.linecorp.armeria.service.test.thrift.main.FileServiceException;
import com.linecorp.armeria.service.test.thrift.main.HeaderService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.service.test.thrift.main.OnewayHelloService;
import com.linecorp.armeria.service.test.thrift.main.TimeService;

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.AsciiString;

@SuppressWarnings("unchecked")
@RunWith(Parameterized.class)
public class ThriftOverHttpClientTest {

    private static final boolean ENABLE_LOGGING_DECORATORS = false;

    private static final Server server;

    private static int httpPort;
    private static int httpsPort;
    private static ClientFactory clientFactoryWithUseHttp2Preface;
    private static ClientFactory clientFactoryWithoutUseHttp2Preface;
    private static ClientOptions clientOptions;

    private static final BlockingQueue<String> serverReceivedNames = new LinkedBlockingQueue<>();

    private static final HelloService.AsyncIface helloHandler = (name, resultHandler)
            -> resultHandler.onComplete("Hello, " + name + '!');

    private static final OnewayHelloService.AsyncIface onewayHelloHandler = (name, resultHandler) -> {
        resultHandler.onComplete(null);
        assertTrue(serverReceivedNames.add(name));
    };

    private static final DevNullService.AsyncIface devNullHandler = (value, resultHandler) -> {
        resultHandler.onComplete(null);
        assertTrue(serverReceivedNames.add(value));
    };

    private static final TimeService.AsyncIface timeServiceHandler =
            resultHandler -> resultHandler.onComplete(System.currentTimeMillis());

    private static final FileService.AsyncIface fileServiceHandler =
            (path, resultHandler) -> resultHandler.onError(Exceptions.clearTrace(new FileServiceException()));

    private static final HeaderService.AsyncIface headerServiceHandler =
            (name, resultHandler) -> {
                final HttpRequest req = RequestContext.current().request();
                resultHandler.onComplete(req.headers().get(AsciiString.of(name), ""));
            };

    private enum Handlers {
        HELLO(helloHandler, HelloService.Iface.class, HelloService.AsyncIface.class),
        ONEWAYHELLO(onewayHelloHandler, OnewayHelloService.Iface.class, OnewayHelloService.AsyncIface.class),
        DEVNULL(devNullHandler, DevNullService.Iface.class, DevNullService.AsyncIface.class),
        TIME(timeServiceHandler, TimeService.Iface.class, TimeService.AsyncIface.class),
        FILE(fileServiceHandler, FileService.Iface.class, FileService.AsyncIface.class),
        HEADER(headerServiceHandler, HeaderService.Iface.class, HeaderService.AsyncIface.class);

        private final Object handler;
        private final Class<?> iface;
        private final Class<?> asyncIface;

        Handlers(Object handler, Class<?> iface, Class<?> asyncIface) {
            this.handler = handler;
            this.iface = iface;
            this.asyncIface = asyncIface;
        }

        Object handler() {
            return handler;
        }

        <T> Class<T> Iface() {
            return (Class<T>) iface;
        }

        <T> Class<T> AsyncIface() {
            return (Class<T>) asyncIface;
        }

        String getPath(SerializationFormat serializationFormat) {
            return '/' + name() + '/' + serializationFormat.name();
        }
    }

    static {
        final SelfSignedCertificate ssc;
        final ServerBuilder sb = new ServerBuilder();

        try {
            sb.port(0, SessionProtocol.HTTP);
            sb.port(0, SessionProtocol.HTTPS);

            ssc = new SelfSignedCertificate("127.0.0.1");
            sb.sslContext(SessionProtocol.HTTPS, ssc.certificate(), ssc.privateKey());

            for (Handlers h : Handlers.values()) {
                for (SerializationFormat defaultSerializationFormat : SerializationFormat.ofThrift()) {
                    Service<HttpRequest, HttpResponse> service =
                            THttpService.of(h.handler(), defaultSerializationFormat);
                    if (ENABLE_LOGGING_DECORATORS) {
                        service = service.decorate(LoggingService::new);
                    }
                    sb.serviceAt(h.getPath(defaultSerializationFormat), service);
                }
            }
        } catch (Exception e) {
            throw new Error(e);
        }
        server = sb.build();
    }

    @Parameterized.Parameters(name = "serFmt: {0}, sessProto: {1}, useHttp2Preface: {3}")
    public static Collection<Object[]> parameters() throws Exception {
        List<Object[]> parameters = new ArrayList<>();
        for (SerializationFormat serializationFormat : SerializationFormat.ofThrift()) {
            parameters.add(new Object[] { serializationFormat, "http",  false, true });
            parameters.add(new Object[] { serializationFormat, "http",  false, false });
            parameters.add(new Object[] { serializationFormat, "https", true,  false });
            parameters.add(new Object[] { serializationFormat, "h1",    true,  false }); // HTTP/1 over TLS
            parameters.add(new Object[] { serializationFormat, "h1c",   false, true  }); // HTTP/1 cleartext
            parameters.add(new Object[] { serializationFormat, "h1c",   false, false });
            parameters.add(new Object[] { serializationFormat, "h2",    true,  false }); // HTTP/2 over TLS
            parameters.add(new Object[] { serializationFormat, "h2c",   false, true  }); // HTTP/2 cleartext
            parameters.add(new Object[] { serializationFormat, "h2c",   false, false });
        }
        return parameters;
    }

    private final SerializationFormat serializationFormat;
    private final String httpProtocol;
    private final boolean useTls;
    private final boolean useHttp2Preface;

    public ThriftOverHttpClientTest(SerializationFormat serializationFormat, String httpProtocol,
                                    boolean useTls, boolean useHttp2Preface) {

        assert !(useTls && useHttp2Preface);

        this.serializationFormat = serializationFormat;
        this.httpProtocol = httpProtocol;
        this.useTls = useTls;
        this.useHttp2Preface = useHttp2Preface;
    }

    @BeforeClass
    public static void init() throws Exception {
        server.start().get();

        httpPort = server.activePorts().values().stream()
                         .filter(p -> p.protocol() == SessionProtocol.HTTP).findAny().get().localAddress()
                         .getPort();
        httpsPort = server.activePorts().values().stream()
                          .filter(p -> p.protocol() == SessionProtocol.HTTPS).findAny().get().localAddress()
                          .getPort();

        final SessionOptionValue<TrustManagerFactory> trustManagerFactoryOptVal =
                SessionOption.TRUST_MANAGER_FACTORY.newValue(InsecureTrustManagerFactory.INSTANCE);

        final SessionOptionValue<Function<KeyedChannelPoolHandler<PoolKey>,
                                                        KeyedChannelPoolHandler<PoolKey>>> poolHandlerDecoratorOptVal =
                SessionOption.POOL_HANDLER_DECORATOR.newValue(
                        ENABLE_LOGGING_DECORATORS ? KeyedChannelPoolLoggingHandler::new
                                                  : Function.identity());

        clientFactoryWithUseHttp2Preface = new THttpClientFactory(
                new HttpClientFactory(SessionOptions.of(
                        trustManagerFactoryOptVal,
                        poolHandlerDecoratorOptVal,
                        SessionOption.USE_HTTP2_PREFACE.newValue(true))));

        clientFactoryWithoutUseHttp2Preface = new THttpClientFactory(
                new HttpClientFactory(SessionOptions.of(
                        trustManagerFactoryOptVal,
                        poolHandlerDecoratorOptVal,
                        SessionOption.USE_HTTP2_PREFACE.newValue(false))));

        if (ENABLE_LOGGING_DECORATORS) {
            clientOptions = ClientOptions.of(
                    ClientOption.DECORATION.newValue(
                            ClientDecoration.of(ThriftCall.class, ThriftReply.class, LoggingClient::new)));
        } else {
            clientOptions = ClientOptions.DEFAULT;
        }
    }

    @AfterClass
    public static void destroy() throws Exception {
        CompletableFuture.runAsync(() -> {
            clientFactoryWithUseHttp2Preface.close();
            clientFactoryWithoutUseHttp2Preface.close();
            server.stop();
        });
    }

    @Before
    public void beforeTest() {
        serverReceivedNames.clear();
    }

    private ClientFactory clientFactory() {
        return useHttp2Preface ? clientFactoryWithUseHttp2Preface
                               : clientFactoryWithoutUseHttp2Preface;
    }

    @Test
    public void testHelloServiceSync() throws Exception {

        HelloService.Iface client = Clients.newClient(clientFactory(), getURI(Handlers.HELLO),
                                                      Handlers.HELLO.Iface(), clientOptions);
        assertEquals("Hello, kukuman!", client.hello("kukuman"));

        for (int i = 0; i < 10; i++) {
            assertEquals("Hello, kukuman" + i + '!', client.hello("kukuman" + i));
        }
    }

    @Test(timeout = 10000)
    public void testHelloServiceAsync() throws Exception {
        HelloService.AsyncIface client =
                Clients.newClient(clientFactory(), getURI(Handlers.HELLO), Handlers.HELLO.AsyncIface(),
                                  clientOptions);

        final int testCount = 10;
        final BlockingQueue<AbstractMap.SimpleEntry<Integer, ?>> resultQueue =
                new LinkedBlockingDeque<>(testCount);
        for (int i = 0; i < testCount; i++) {
            final int num = i;
            client.hello("kukuman" + num, new AsyncMethodCallback<String>() {
                @Override
                public void onComplete(String response) {
                    assertTrue(resultQueue.add(new AbstractMap.SimpleEntry<>(num, response)));
                }

                @Override
                public void onError(Exception exception) {
                    assertTrue(resultQueue.add(new AbstractMap.SimpleEntry<>(num, exception)));
                }
            });
        }
        for (int i = 0; i < testCount; i++) {
            AbstractMap.SimpleEntry<Integer, ?> pair = resultQueue.take();
            assertEquals("Hello, kukuman" + pair.getKey() + '!', pair.getValue());
        }
    }

    @Test(timeout = 10000)
    public void testOnewayHelloServiceSync() throws Exception {
        OnewayHelloService.Iface client =
                Clients.newClient(clientFactory(), getURI(Handlers.ONEWAYHELLO),
                                  Handlers.ONEWAYHELLO.Iface(), clientOptions);
        client.hello("kukuman");
        client.hello("kukuman2");
        assertEquals("kukuman", serverReceivedNames.take());
        assertEquals("kukuman2", serverReceivedNames.take());
    }

    @Test(timeout = 10000)
    public void testOnewayHelloServiceAsync() throws Exception {
        OnewayHelloService.AsyncIface client =
                Clients.newClient(clientFactory(), getURI(Handlers.ONEWAYHELLO),
                                  Handlers.ONEWAYHELLO.AsyncIface(), clientOptions);
        BlockingQueue<Object> resQueue = new LinkedBlockingQueue<>();

        String[] names = { "kukuman", "kukuman2" };
        for (String name : names) {
            client.hello(name, new RequestQueuingCallback(resQueue));
        }

        for (String ignored : names) {
            assertEquals("null", resQueue.take());
        }

        for (String ignored : names) {
            assertThat(serverReceivedNames.take(), isOneOf(names));
        }
    }

    @Test(timeout = 10000)
    public void testDevNullServiceSync() throws Exception {
        DevNullService.Iface client =
                Clients.newClient(clientFactory(), getURI(Handlers.DEVNULL), Handlers.DEVNULL.Iface(),
                                  clientOptions);
        client.consume("kukuman");
        client.consume("kukuman2");
        assertEquals("kukuman", serverReceivedNames.take());
        assertEquals("kukuman2", serverReceivedNames.take());
    }

    @Test(timeout = 10000)
    public void testDevNullServiceAsync() throws Exception {
        DevNullService.AsyncIface client =
                Clients.newClient(clientFactory(), getURI(Handlers.DEVNULL),
                                  Handlers.DEVNULL.AsyncIface(), clientOptions);
        BlockingQueue<Object> resQueue = new LinkedBlockingQueue<>();

        String[] names = { "kukuman", "kukuman2" };
        for (String name : names) {
            client.consume(name, new RequestQueuingCallback(resQueue));
        }

        for (String ignored : names) {
            assertEquals("null", resQueue.take());
        }

        for (String ignored : names) {
            assertThat(serverReceivedNames.take(), isOneOf(names));
        }
    }

    @Test(timeout = 10000)
    public void testTimeServiceSync() throws Exception {
        TimeService.Iface client =
                Clients.newClient(clientFactory(), getURI(Handlers.TIME), Handlers.TIME.Iface(),
                                  clientOptions);

        long serverTime = client.getServerTime();
        assertThat(serverTime, lessThanOrEqualTo(System.currentTimeMillis()));
    }

    @Test(timeout = 10000)
    public void testTimeServiceAsync() throws Exception {
        TimeService.AsyncIface client =
                Clients.newClient(clientFactory(), getURI(Handlers.TIME), Handlers.TIME.AsyncIface(),
                                  clientOptions);

        BlockingQueue<Object> resQueue = new LinkedBlockingQueue<>();
        client.getServerTime(new RequestQueuingCallback(resQueue));

        final Object result = resQueue.take();
        assertThat(result, is(instanceOf(Long.class)));
        assertThat((Long) result, lessThanOrEqualTo(System.currentTimeMillis()));
    }

    @Test(timeout = 10000, expected = FileServiceException.class)
    public void testFileServiceSync() throws Exception {
        FileService.Iface client =
                Clients.newClient(clientFactory(), getURI(Handlers.FILE), Handlers.FILE.Iface(),
                                  clientOptions);

        client.create("test");
    }

    @Test(timeout = 10000)
    public void testFileServiceAsync() throws Exception {
        FileService.AsyncIface client =
                Clients.newClient(clientFactory(), getURI(Handlers.FILE), Handlers.FILE.AsyncIface(),
                                  clientOptions);

        BlockingQueue<Object> resQueue = new LinkedBlockingQueue<>();
        client.create("test", new RequestQueuingCallback(resQueue));

        assertThat(resQueue.take(), instanceOf(FileServiceException.class));
    }

    @Test(timeout = 10000)
    public void testDerivedClient() throws Exception {
        final String AUTHORIZATION = "authorization";
        final String NO_TOKEN = "";
        final String TOKEN_A = "token 1234";
        final String TOKEN_B = "token 5678";

        final HeaderService.Iface client = Clients.newClient(clientFactory(), getURI(Handlers.HEADER),
                                                             Handlers.HEADER.Iface(), clientOptions);

        assertThat(client.header(AUTHORIZATION), is(NO_TOKEN));

        final HeaderService.Iface clientA =
                Clients.newDerivedClient(client, newHttpHeaderOption(AsciiString.of(AUTHORIZATION), TOKEN_A));

        final HeaderService.Iface clientB =
                Clients.newDerivedClient(client, newHttpHeaderOption(AsciiString.of(AUTHORIZATION), TOKEN_B));

        assertThat(clientA.header(AUTHORIZATION), is(TOKEN_A));
        assertThat(clientB.header(AUTHORIZATION), is(TOKEN_B));

        // Ensure that the parent client's HTTP_HEADERS option did not change:
        assertThat(client.header(AUTHORIZATION), is(NO_TOKEN));
    }

    private static ClientOptionValue<HttpHeaders> newHttpHeaderOption(AsciiString name, String value) {
        return ClientOption.HTTP_HEADERS.newValue(HttpHeaders.of(name, value));
    }

    private String getURI(Handlers handler) {
        int port = useTls ? httpsPort : httpPort;
        return serializationFormat.uriText() + '+' + httpProtocol + "://127.0.0.1:" + port + handler.getPath(
                serializationFormat);
    }

    @SuppressWarnings("rawtypes")
    private static class RequestQueuingCallback implements AsyncMethodCallback {

        private final BlockingQueue<Object> resQueue;

        RequestQueuingCallback(BlockingQueue<Object> resQueue) {
            this.resQueue = resQueue;
        }

        @Override
        public void onComplete(Object response) {
            assertTrue(resQueue.add(response == null ? "null" : response));
        }

        @Override
        public void onError(Exception exception) {
            assertTrue(resQueue.add(exception));
        }
    }
}
