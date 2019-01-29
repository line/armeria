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

package com.linecorp.armeria.client.thrift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.apache.thrift.protocol.TMessageType;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import com.linecorp.armeria.client.ClientDecorationBuilder;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.ClientOption;
import com.linecorp.armeria.client.ClientOptionValue;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.ConnectionPoolListener;
import com.linecorp.armeria.client.logging.ConnectionPoolLoggingListener;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.common.thrift.ThriftCall;
import com.linecorp.armeria.common.thrift.ThriftReply;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.main.BinaryService;
import com.linecorp.armeria.service.test.thrift.main.DevNullService;
import com.linecorp.armeria.service.test.thrift.main.FileService;
import com.linecorp.armeria.service.test.thrift.main.FileServiceException;
import com.linecorp.armeria.service.test.thrift.main.HeaderService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.service.test.thrift.main.OnewayHelloService;
import com.linecorp.armeria.service.test.thrift.main.TimeService;

import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.AsciiString;

@SuppressWarnings("unchecked")
@RunWith(Parameterized.class)
public class ThriftOverHttpClientTest {

    private static final boolean ENABLE_LOGGING_DECORATORS = false;
    private static final boolean ENABLE_CONNECTION_POOL_LOGGING = true;

    private static final Server server;

    private static int httpPort;
    private static int httpsPort;
    private static ClientFactory clientFactoryWithUseHttp2Preface;
    private static ClientFactory clientFactoryWithoutUseHttp2Preface;
    private static ClientOptions clientOptions;

    private static final BlockingQueue<String> serverReceivedNames = new LinkedBlockingQueue<>();

    private static volatile boolean recordMessageLogs;
    private static final BlockingQueue<RequestLog> requestLogs = new LinkedBlockingQueue<>();

    private static final HelloService.AsyncIface helloHandler = (name, resultHandler)
            -> resultHandler.onComplete("Hello, " + name + '!');

    private static final HelloService.AsyncIface exceptionThrowingHandler = (name, resultHandler)
            -> resultHandler.onError(new Exception(name));

    private static final OnewayHelloService.AsyncIface exceptionThrowingOnewayHandler =
            (name, resultHandler) -> {
                assertThat(serverReceivedNames.add(name)).isTrue();
                resultHandler.onError(new Exception(name));
            };

    private static final OnewayHelloService.AsyncIface onewayHelloHandler = (name, resultHandler) -> {
        resultHandler.onComplete(null);
        assertThat(serverReceivedNames.add(name)).isTrue();
    };
    private static final DevNullService.AsyncIface devNullHandler = (value, resultHandler) -> {
        resultHandler.onComplete(null);
        assertThat(serverReceivedNames.add(value)).isTrue();
    };

    private static final BinaryService.Iface binaryHandler = data -> {
        ByteBuffer result = ByteBuffer.allocate(data.remaining());
        for (int i = data.position(), j = 0; i < data.limit(); i++, j++) {
            result.put(j, (byte) (data.get(i) + 1));
        }
        return result;
    };

    private static final TimeService.AsyncIface timeServiceHandler =
            resultHandler -> resultHandler.onComplete(System.currentTimeMillis());

    private static final FileService.AsyncIface fileServiceHandler =
            (path, resultHandler) -> resultHandler.onError(Exceptions.clearTrace(new FileServiceException()));

    private static final HeaderService.AsyncIface headerServiceHandler =
            (name, resultHandler) -> {
                final HttpRequest req = RequestContext.current().request();
                resultHandler.onComplete(req.headers().get(HttpHeaderNames.of(name), ""));
            };

    private enum Handlers {
        HELLO(helloHandler, HelloService.Iface.class, HelloService.AsyncIface.class),
        EXCEPTION(exceptionThrowingHandler, HelloService.Iface.class, HelloService.AsyncIface.class),
        ONEWAYHELLO(onewayHelloHandler, OnewayHelloService.Iface.class, OnewayHelloService.AsyncIface.class),
        EXCEPTION_ONEWAY(exceptionThrowingOnewayHandler, OnewayHelloService.Iface.class,
                         OnewayHelloService.AsyncIface.class),
        DEVNULL(devNullHandler, DevNullService.Iface.class, DevNullService.AsyncIface.class),
        BINARY(binaryHandler, BinaryService.Iface.class, BinaryService.AsyncIface.class),
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

        <T> Class<T> iface() {
            return (Class<T>) iface;
        }

        <T> Class<T> asyncIface() {
            return (Class<T>) asyncIface;
        }

        String path(SerializationFormat serializationFormat) {
            return '/' + name() + '/' + serializationFormat.uriText();
        }
    }

    static {
        final ServerBuilder sb = new ServerBuilder();

        try {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();

            for (Handlers h : Handlers.values()) {
                for (SerializationFormat defaultSerializationFormat : ThriftSerializationFormats.values()) {
                    Service<HttpRequest, HttpResponse> service =
                            THttpService.of(h.handler(), defaultSerializationFormat);
                    if (ENABLE_LOGGING_DECORATORS) {
                        service = service.decorate(LoggingService.newDecorator());
                    }
                    sb.service(h.path(defaultSerializationFormat), service);
                }
            }
        } catch (Exception e) {
            throw new Error(e);
        }
        server = sb.build();
    }

    @Parameterized.Parameters(name = "serFmt: {0}, sessProto: {1}, useHttp2Preface: {3}")
    public static Collection<Object[]> parameters() throws Exception {
        final List<Object[]> parameters = new ArrayList<>();
        for (SerializationFormat serializationFormat : ThriftSerializationFormats.values()) {
            parameters.add(new Object[] { serializationFormat, "http", false, true });
            parameters.add(new Object[] { serializationFormat, "http", false, false });
            parameters.add(new Object[] { serializationFormat, "https", true, false });
            parameters.add(new Object[] { serializationFormat, "h1", true, false }); // HTTP/1 over TLS
            parameters.add(new Object[] { serializationFormat, "h1c", false, false }); // HTTP/1 cleartext
            parameters.add(new Object[] { serializationFormat, "h2", true, false }); // HTTP/2 over TLS
            parameters.add(new Object[] { serializationFormat, "h2c", false, true }); // HTTP/2 cleartext
            parameters.add(new Object[] { serializationFormat, "h2c", false, false });
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
                         .filter(ServerPort::hasHttp).findAny().get().localAddress()
                         .getPort();
        httpsPort = server.activePorts().values().stream()
                          .filter(ServerPort::hasHttps).findAny().get().localAddress()
                          .getPort();

        final Consumer<SslContextBuilder> sslContextCustomizer =
                b -> b.trustManager(InsecureTrustManagerFactory.INSTANCE);

        final ConnectionPoolListener connectionPoolListener =
                ENABLE_CONNECTION_POOL_LOGGING ? new ConnectionPoolLoggingListener()
                                               : ConnectionPoolListener.noop();

        clientFactoryWithUseHttp2Preface = new ClientFactoryBuilder()
                .sslContextCustomizer(sslContextCustomizer)
                .connectionPoolListener(connectionPoolListener)
                .useHttp2Preface(true)
                .build();

        clientFactoryWithoutUseHttp2Preface = new ClientFactoryBuilder()
                .sslContextCustomizer(sslContextCustomizer)
                .connectionPoolListener(connectionPoolListener)
                .useHttp2Preface(false)
                .build();

        final ClientDecorationBuilder decoBuilder = new ClientDecorationBuilder();
        decoBuilder.addRpc((delegate, ctx, req) -> {
            if (recordMessageLogs) {
                ctx.log().addListener(requestLogs::add, RequestLogAvailability.COMPLETE);
            }
            return delegate.execute(ctx, req);
        });

        if (ENABLE_LOGGING_DECORATORS) {
            decoBuilder.addRpc(LoggingClient.newDecorator());
        }

        clientOptions = ClientOptions.of(ClientOption.DECORATION.newValue(decoBuilder.build()));
    }

    @AfterClass
    public static void destroy() throws Exception {
        clientFactoryWithUseHttp2Preface.close();
        clientFactoryWithoutUseHttp2Preface.close();
        server.stop();
    }

    @Before
    public void beforeTest() {
        serverReceivedNames.clear();

        recordMessageLogs = false;
        requestLogs.clear();
    }

    private ClientFactory clientFactory() {
        return useHttp2Preface ? clientFactoryWithUseHttp2Preface
                               : clientFactoryWithoutUseHttp2Preface;
    }

    @Test(timeout = 10000)
    public void testHelloServiceSync() throws Exception {

        final HelloService.Iface client = Clients.newClient(clientFactory(), getURI(Handlers.HELLO),
                                                            Handlers.HELLO.iface(), clientOptions);
        assertThat(client.hello("kukuman")).isEqualTo("Hello, kukuman!");
        assertThat(client.hello(null)).isEqualTo("Hello, null!");

        for (int i = 0; i < 10; i++) {
            assertThat(client.hello("kukuman" + i)).isEqualTo("Hello, kukuman" + i + '!');
        }
    }

    @Test(timeout = 10000)
    public void testHelloServiceAsync() throws Exception {
        final HelloService.AsyncIface client =
                Clients.newClient(clientFactory(), getURI(Handlers.HELLO), Handlers.HELLO.asyncIface(),
                                  clientOptions);

        final int testCount = 10;
        final BlockingQueue<AbstractMap.SimpleEntry<Integer, ?>> resultQueue =
                new LinkedBlockingDeque<>(testCount);
        for (int i = 0; i < testCount; i++) {
            final int num = i;
            client.hello("kukuman" + num, new AsyncMethodCallback<String>() {
                @Override
                public void onComplete(String response) {
                    assertThat(resultQueue.add(new AbstractMap.SimpleEntry<>(num, response))).isTrue();
                }

                @Override
                public void onError(Exception exception) {
                    assertThat(resultQueue.add(new AbstractMap.SimpleEntry<>(num, exception))).isTrue();
                }
            });
        }
        for (int i = 0; i < testCount; i++) {
            final AbstractMap.SimpleEntry<Integer, ?> pair = resultQueue.take();
            assertThat(pair.getValue()).isEqualTo("Hello, kukuman" + pair.getKey() + '!');
        }
    }

    @Test(timeout = 10000)
    public void testOnewayHelloServiceSync() throws Exception {
        final OnewayHelloService.Iface client =
                Clients.newClient(clientFactory(), getURI(Handlers.ONEWAYHELLO),
                                  Handlers.ONEWAYHELLO.iface(), clientOptions);
        client.hello("kukuman");
        client.hello("kukuman2");
        assertThat(serverReceivedNames.take()).isEqualTo("kukuman");
        assertThat(serverReceivedNames.take()).isEqualTo("kukuman2");
    }

    @Test(timeout = 10000)
    public void testOnewayHelloServiceAsync() throws Exception {
        final OnewayHelloService.AsyncIface client =
                Clients.newClient(clientFactory(), getURI(Handlers.ONEWAYHELLO),
                                  Handlers.ONEWAYHELLO.asyncIface(), clientOptions);
        final BlockingQueue<Object> resQueue = new LinkedBlockingQueue<>();

        final String[] names = { "kukuman", "kukuman2" };
        for (String name : names) {
            client.hello(name, new RequestQueuingCallback(resQueue));
        }

        for (String ignored : names) {
            assertThat(resQueue.take()).isEqualTo("null");
        }

        for (String ignored : names) {
            assertThat(serverReceivedNames.take()).isIn(names);
        }
    }

    @Test(timeout = 10000)
    public void testExceptionThrowingOnewayServiceSync() throws Exception {
        final OnewayHelloService.Iface client =
                Clients.newClient(clientFactory(), getURI(Handlers.EXCEPTION_ONEWAY),
                                  Handlers.EXCEPTION_ONEWAY.iface(), clientOptions);
        client.hello("kukuman");
        client.hello("kukuman2");
        assertThat(serverReceivedNames.take()).isEqualTo("kukuman");
        assertThat(serverReceivedNames.take()).isEqualTo("kukuman2");
    }

    @Test(timeout = 10000)
    public void testExceptionThrowingOnewayServiceAsync() throws Exception {
        final OnewayHelloService.AsyncIface client =
                Clients.newClient(clientFactory(), getURI(Handlers.EXCEPTION_ONEWAY),
                                  Handlers.EXCEPTION_ONEWAY.asyncIface(), clientOptions);
        final BlockingQueue<Object> resQueue = new LinkedBlockingQueue<>();

        final String[] names = { "kukuman", "kukuman2" };
        for (String name : names) {
            client.hello(name, new RequestQueuingCallback(resQueue));
        }

        for (String ignored : names) {
            assertThat(resQueue.take()).isEqualTo("null");
        }

        for (String ignored : names) {
            assertThat(serverReceivedNames.take()).isIn(names);
        }
    }

    @Test(timeout = 10000)
    public void testDevNullServiceSync() throws Exception {
        final DevNullService.Iface client =
                Clients.newClient(clientFactory(), getURI(Handlers.DEVNULL), Handlers.DEVNULL.iface(),
                                  clientOptions);
        client.consume("kukuman");
        client.consume("kukuman2");
        assertThat(serverReceivedNames.take()).isEqualTo("kukuman");
        assertThat(serverReceivedNames.take()).isEqualTo("kukuman2");
    }

    @Test(timeout = 10000)
    public void testDevNullServiceAsync() throws Exception {
        final DevNullService.AsyncIface client =
                Clients.newClient(clientFactory(), getURI(Handlers.DEVNULL),
                                  Handlers.DEVNULL.asyncIface(), clientOptions);
        final BlockingQueue<Object> resQueue = new LinkedBlockingQueue<>();

        final String[] names = { "kukuman", "kukuman2" };
        for (String name : names) {
            client.consume(name, new RequestQueuingCallback(resQueue));
        }

        for (String ignored : names) {
            assertThat(resQueue.take()).isEqualTo("null");
        }

        for (String ignored : names) {
            assertThat(serverReceivedNames.take()).isIn(names);
        }
    }

    @Test(timeout = 10000)
    public void testBinaryServiceSync() throws Exception {
        final BinaryService.Iface client = Clients.newClient(clientFactory(),
                                                             getURI(Handlers.BINARY), Handlers.BINARY.iface(),
                                                             clientOptions);

        final ByteBuffer result = client.process(ByteBuffer.wrap(new byte[] { 1, 2 }));
        final List<Byte> out = new ArrayList<>();
        for (int i = result.position(); i < result.limit(); i++) {
            out.add(result.get(i));
        }
        assertThat(out).containsExactly((byte) 2, (byte) 3);
    }

    @Test(timeout = 10000)
    public void testTimeServiceSync() throws Exception {
        final TimeService.Iface client =
                Clients.newClient(clientFactory(), getURI(Handlers.TIME), Handlers.TIME.iface(),
                                  clientOptions);

        final long serverTime = client.getServerTime();
        assertThat(serverTime).isLessThanOrEqualTo(System.currentTimeMillis());
    }

    @Test(timeout = 10000)
    public void testTimeServiceAsync() throws Exception {
        final TimeService.AsyncIface client =
                Clients.newClient(clientFactory(), getURI(Handlers.TIME), Handlers.TIME.asyncIface(),
                                  clientOptions);

        final BlockingQueue<Object> resQueue = new LinkedBlockingQueue<>();
        client.getServerTime(new RequestQueuingCallback(resQueue));

        final Object result = resQueue.take();
        assertThat(result).isInstanceOf(Long.class);
        assertThat((Long) result).isLessThanOrEqualTo(System.currentTimeMillis());
    }

    @Test(timeout = 10000, expected = FileServiceException.class)
    public void testFileServiceSync() throws Exception {
        final FileService.Iface client =
                Clients.newClient(clientFactory(), getURI(Handlers.FILE), Handlers.FILE.iface(),
                                  clientOptions);

        client.create("test");
    }

    @Test(timeout = 10000)
    public void testFileServiceAsync() throws Exception {
        final FileService.AsyncIface client =
                Clients.newClient(clientFactory(), getURI(Handlers.FILE), Handlers.FILE.asyncIface(),
                                  clientOptions);

        final BlockingQueue<Object> resQueue = new LinkedBlockingQueue<>();
        client.create("test", new RequestQueuingCallback(resQueue));

        assertThat(resQueue.take()).isInstanceOf(FileServiceException.class);
    }

    @Test(timeout = 10000)
    public void testDerivedClient() throws Exception {
        final String AUTHORIZATION = "authorization";
        final String NO_TOKEN = "";
        final String TOKEN_A = "token 1234";
        final String TOKEN_B = "token 5678";

        final HeaderService.Iface client = Clients.newClient(clientFactory(), getURI(Handlers.HEADER),
                                                             Handlers.HEADER.iface(), clientOptions);

        assertThat(client.header(AUTHORIZATION)).isEqualTo(NO_TOKEN);

        final HeaderService.Iface clientA =
                Clients.newDerivedClient(client,
                                         newHttpHeaderOption(HttpHeaderNames.of(AUTHORIZATION), TOKEN_A));

        final HeaderService.Iface clientB =
                Clients.newDerivedClient(client,
                                         newHttpHeaderOption(HttpHeaderNames.of(AUTHORIZATION), TOKEN_B));

        assertThat(clientA.header(AUTHORIZATION)).isEqualTo(TOKEN_A);
        assertThat(clientB.header(AUTHORIZATION)).isEqualTo(TOKEN_B);

        // Ensure that the parent client's HTTP_HEADERS option did not change:
        assertThat(client.header(AUTHORIZATION)).isEqualTo(NO_TOKEN);
    }

    @Test(timeout = 10000)
    public void testMessageLogsForCall() throws Exception {
        final HelloService.Iface client = Clients.newClient(clientFactory(), getURI(Handlers.HELLO),
                                                            Handlers.HELLO.iface(), clientOptions);
        recordMessageLogs = true;
        client.hello("trustin");

        final RequestLog log = requestLogs.take();

        assertThat(log.requestHeaders()).isInstanceOf(HttpHeaders.class);
        assertThat(log.requestContent()).isInstanceOf(RpcRequest.class);
        assertThat(log.rawRequestContent()).isInstanceOf(ThriftCall.class);

        final RpcRequest request = (RpcRequest) log.requestContent();
        assertThat(request.serviceType()).isEqualTo(HelloService.Iface.class);
        assertThat(request.method()).isEqualTo("hello");
        assertThat(request.params()).containsExactly("trustin");

        final ThriftCall rawRequest = (ThriftCall) log.rawRequestContent();
        assertThat(rawRequest.header().type).isEqualTo(TMessageType.CALL);
        assertThat(rawRequest.header().name).isEqualTo("hello");
        assertThat(rawRequest.args()).isInstanceOf(HelloService.hello_args.class);
        assertThat(((HelloService.hello_args) rawRequest.args()).getName()).isEqualTo("trustin");

        assertThat(log.responseHeaders()).isInstanceOf(HttpHeaders.class);
        assertThat(log.responseContent()).isInstanceOf(RpcResponse.class);
        assertThat(log.rawResponseContent()).isInstanceOf(ThriftReply.class);

        final RpcResponse response = (RpcResponse) log.responseContent();
        assertThat(response.get()).isEqualTo("Hello, trustin!");

        final ThriftReply rawResponse = (ThriftReply) log.rawResponseContent();
        assertThat(rawResponse.header().type).isEqualTo(TMessageType.REPLY);
        assertThat(rawResponse.header().name).isEqualTo("hello");
        assertThat(rawResponse.result()).isInstanceOf(HelloService.hello_result.class);
        assertThat(((HelloService.hello_result) rawResponse.result()).getSuccess())
                .isEqualTo("Hello, trustin!");
    }

    @Test(timeout = 10000)
    public void testMessageLogsForOneWay() throws Exception {
        final OnewayHelloService.Iface client = Clients.newClient(clientFactory(), getURI(Handlers.HELLO),
                                                                  Handlers.ONEWAYHELLO.iface(), clientOptions);
        recordMessageLogs = true;
        client.hello("trustin");

        final RequestLog log = requestLogs.take();

        assertThat(log.requestHeaders()).isInstanceOf(HttpHeaders.class);
        assertThat(log.requestContent()).isInstanceOf(RpcRequest.class);
        assertThat(log.rawRequestContent()).isInstanceOf(ThriftCall.class);

        final RpcRequest request = (RpcRequest) log.requestContent();
        assertThat(request.serviceType()).isEqualTo(OnewayHelloService.Iface.class);
        assertThat(request.method()).isEqualTo("hello");
        assertThat(request.params()).containsExactly("trustin");

        final ThriftCall rawRequest = (ThriftCall) log.rawRequestContent();
        assertThat(rawRequest.header().type).isEqualTo(TMessageType.ONEWAY);
        assertThat(rawRequest.header().name).isEqualTo("hello");
        assertThat(rawRequest.args()).isInstanceOf(OnewayHelloService.hello_args.class);
        assertThat(((OnewayHelloService.hello_args) rawRequest.args()).getName()).isEqualTo("trustin");

        assertThat(log.responseHeaders()).isInstanceOf(HttpHeaders.class);
        assertThat(log.responseContent()).isInstanceOf(RpcResponse.class);
        assertThat(log.rawResponseContent()).isNull();

        final RpcResponse response = (RpcResponse) log.responseContent();
        assertThat(response.get()).isNull();
    }

    @Test(timeout = 10000)
    public void testMessageLogsForException() throws Exception {
        final HelloService.Iface client = Clients.newClient(clientFactory(), getURI(Handlers.EXCEPTION),
                                                            Handlers.EXCEPTION.iface(), clientOptions);
        recordMessageLogs = true;

        assertThatThrownBy(() -> client.hello("trustin")).isInstanceOf(TApplicationException.class);

        final RequestLog log = requestLogs.take();

        assertThat(log.requestHeaders()).isInstanceOf(HttpHeaders.class);
        assertThat(log.requestContent()).isInstanceOf(RpcRequest.class);
        assertThat(log.rawRequestContent()).isInstanceOf(ThriftCall.class);

        final RpcRequest request = (RpcRequest) log.requestContent();
        assertThat(request.serviceType()).isEqualTo(HelloService.Iface.class);
        assertThat(request.method()).isEqualTo("hello");
        assertThat(request.params()).containsExactly("trustin");

        final ThriftCall rawRequest = (ThriftCall) log.rawRequestContent();
        assertThat(rawRequest.header().type).isEqualTo(TMessageType.CALL);
        assertThat(rawRequest.header().name).isEqualTo("hello");
        assertThat(rawRequest.args()).isInstanceOf(HelloService.hello_args.class);
        assertThat(((HelloService.hello_args) rawRequest.args()).getName()).isEqualTo("trustin");

        assertThat(log.responseHeaders()).isInstanceOf(HttpHeaders.class);
        assertThat(log.responseContent()).isInstanceOf(RpcResponse.class);
        assertThat(log.rawResponseContent()).isInstanceOf(ThriftReply.class);

        final RpcResponse response = (RpcResponse) log.responseContent();
        assertThat(response.cause()).isNotNull();

        final ThriftReply rawResponse = (ThriftReply) log.rawResponseContent();
        assertThat(rawResponse.header().type).isEqualTo(TMessageType.EXCEPTION);
        assertThat(rawResponse.header().name).isEqualTo("hello");
        assertThat(rawResponse.exception()).isNotNull();
    }

    private static ClientOptionValue<HttpHeaders> newHttpHeaderOption(AsciiString name, String value) {
        return ClientOption.HTTP_HEADERS.newValue(HttpHeaders.of(name, value));
    }

    private String getURI(Handlers handler) {
        final int port = useTls ? httpsPort : httpPort;
        return serializationFormat.uriText() + '+' + httpProtocol + "://127.0.0.1:" + port + handler.path(
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
            assertThat(resQueue.add(response == null ? "null" : response)).isTrue();
        }

        @Override
        public void onError(Exception exception) {
            assertThat(resQueue.add(exception)).isTrue();
        }
    }
}
