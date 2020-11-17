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

import static com.linecorp.armeria.common.MediaType.create;
import static com.linecorp.armeria.common.thrift.ThriftProtocolFactories.getThriftSerializationFormats;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TTupleProtocol;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.ClientDecoration;
import com.linecorp.armeria.client.ClientDecorationBuilder;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptionValue;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.ConnectionPoolListener;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.InvalidResponseHeadersException;
import com.linecorp.armeria.client.logging.LoggingRpcClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SerializationFormatProvider;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.thrift.ThriftCall;
import com.linecorp.armeria.common.thrift.ThriftFuture;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactoryProvider;
import com.linecorp.armeria.common.thrift.ThriftReply;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
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
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.AsciiString;

@SuppressWarnings("unchecked")
public class ThriftOverHttpClientTest extends SerializationFormatProvider
        implements ThriftProtocolFactoryProvider {

    private static final boolean ENABLE_LOGGING_DECORATORS = false;
    private static final boolean ENABLE_CONNECTION_POOL_LOGGING = true;

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
        final ByteBuffer result = ByteBuffer.allocate(data.remaining());
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
                resultHandler.onComplete(ServiceRequestContext.current().request()
                                                              .headers().get(HttpHeaderNames.of(name), ""));
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

    @Override
    protected Set<Entry> entries() {
        return ImmutableSet.of(
                new Entry("ttuple",
                          create("application", "x-thrift").withParameter("protocol", "TTUPLE"),
                          create("application", "vnd.apache.thrift.tuple"))
        );
    }

    @Override
    public Set<ThriftSerializationFormat> thriftSerializationFormats() {
        return ImmutableSet.of(
                new ThriftSerializationFormat(SerializationFormat.of("ttuple"), new TTupleProtocol.Factory()));
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();

            for (Handlers h : Handlers.values()) {
                for (SerializationFormat thriftSerializationFormat : getThriftSerializationFormats()) {
                    HttpService service = THttpService.of(h.handler(), thriftSerializationFormat);
                    if (ENABLE_LOGGING_DECORATORS) {
                        service = service.decorate(LoggingService.newDecorator());
                    }
                    sb.service(h.path(thriftSerializationFormat), service);
                }
            }

            sb.service("/500", (ctx, req) -> HttpResponse.of(500));
        }
    };

    @BeforeAll
    static void init() throws Exception {
        final ConnectionPoolListener connectionPoolListener =
                ENABLE_CONNECTION_POOL_LOGGING ? ConnectionPoolListener.logging()
                                               : ConnectionPoolListener.noop();

        clientFactoryWithUseHttp2Preface = ClientFactory.builder()
                                                        .tlsNoVerify()
                                                        .connectionPoolListener(connectionPoolListener)
                                                        .useHttp2Preface(true)
                                                        .build();

        clientFactoryWithoutUseHttp2Preface = ClientFactory.builder()
                                                           .tlsNoVerify()
                                                           .connectionPoolListener(connectionPoolListener)
                                                           .useHttp2Preface(false)
                                                           .build();

        final ClientDecorationBuilder decoBuilder = ClientDecoration.builder();
        decoBuilder.addRpc((delegate, ctx, req) -> {
            if (recordMessageLogs) {
                ctx.log().whenComplete().thenAccept(requestLogs::add);
            }
            return delegate.execute(ctx, req);
        });

        if (ENABLE_LOGGING_DECORATORS) {
            decoBuilder.addRpc(LoggingRpcClient.newDecorator());
        }

        clientOptions = ClientOptions.of(ClientOptions.DECORATION.newValue(decoBuilder.build()));
    }

    @AfterAll
    static void destroy() throws Exception {
        clientFactoryWithUseHttp2Preface.close();
        clientFactoryWithoutUseHttp2Preface.close();
        server.stop();
    }

    @BeforeEach
    void beforeTest() {
        serverReceivedNames.clear();

        recordMessageLogs = false;
        requestLogs.clear();
    }

    @ParameterizedTest
    @ArgumentsSource(ParametersProvider.class)
    void testHelloServiceSync(
            ClientOptions clientOptions, SerializationFormat format, SessionProtocol protocol)
            throws Exception {
        final HelloService.Iface client = Clients.builder(uri(Handlers.HELLO, format, protocol))
                                                 .options(clientOptions)
                                                 .build(Handlers.HELLO.iface());
        assertThat(client.hello("kukuman")).isEqualTo("Hello, kukuman!");
        assertThat(client.hello(null)).isEqualTo("Hello, null!");

        for (int i = 0; i < 10; i++) {
            assertThat(client.hello("kukuman" + i)).isEqualTo("Hello, kukuman" + i + '!');
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ParametersProvider.class)
    void testHelloServiceAsync(
            ClientOptions clientOptions, SerializationFormat format, SessionProtocol protocol)
            throws Exception {
        final HelloService.AsyncIface client =
                Clients.builder(uri(Handlers.HELLO, format, protocol))
                       .options(clientOptions)
                       .build(Handlers.HELLO.asyncIface());

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

    @ParameterizedTest
    @ArgumentsSource(ParametersProvider.class)
    void contextCaptorSync(
            ClientOptions clientOptions, SerializationFormat format, SessionProtocol protocol)
            throws Exception {
        final HelloService.Iface client = Clients.builder(uri(Handlers.HELLO, format, protocol))
                                                 .options(clientOptions)
                                                 .build(Handlers.HELLO.iface());
        try (ClientRequestContextCaptor ctxCaptor = Clients.newContextCaptor()) {
            client.hello("kukuman");
            final ClientRequestContext ctx = ctxCaptor.get();
            final RpcRequest rpcReq = ctx.rpcRequest();
            assertThat(rpcReq).isNotNull();
            assertThat(rpcReq.method()).isEqualTo("hello");
            assertThat(rpcReq.params()).containsExactly("kukuman");
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ParametersProvider.class)
    void contextCaptorAsync(
            ClientOptions clientOptions, SerializationFormat format, SessionProtocol protocol)
            throws Exception {
        final HelloService.AsyncIface client =
                Clients.builder(uri(Handlers.HELLO, format, protocol))
                       .options(clientOptions)
                       .build(Handlers.HELLO.asyncIface());

        try (ClientRequestContextCaptor ctxCaptor = Clients.newContextCaptor()) {
            client.hello("kukuman", new ThriftFuture<>());
            final ClientRequestContext ctx = ctxCaptor.get();
            final RpcRequest rpcReq = ctx.rpcRequest();
            assertThat(rpcReq).isNotNull();
            assertThat(rpcReq.method()).isEqualTo("hello");
            assertThat(rpcReq.params()).containsExactly("kukuman");
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ParametersProvider.class)
    void testOnewayHelloServiceSync(
            ClientOptions clientOptions, SerializationFormat format, SessionProtocol protocol)
            throws Exception {
        final OnewayHelloService.Iface client =
                Clients.builder(uri(Handlers.ONEWAYHELLO, format, protocol))
                       .options(clientOptions)
                       .build(Handlers.ONEWAYHELLO.iface());
        client.hello("kukuman");
        client.hello("kukuman2");
        assertThat(serverReceivedNames.take()).isEqualTo("kukuman");
        assertThat(serverReceivedNames.take()).isEqualTo("kukuman2");
    }

    @ParameterizedTest
    @ArgumentsSource(ParametersProvider.class)
    void testOnewayHelloServiceAsync(
            ClientOptions clientOptions, SerializationFormat format, SessionProtocol protocol)
            throws Exception {
        final OnewayHelloService.AsyncIface client =
                Clients.builder(uri(Handlers.ONEWAYHELLO, format, protocol))
                       .options(clientOptions)
                       .build(Handlers.ONEWAYHELLO.asyncIface());
        final BlockingQueue<Object> resQueue = new LinkedBlockingQueue<>();

        final String[] names = { "kukuman", "kukuman2" };
        for (String name : names) {
            client.hello(name, new RequestQueuingCallback(resQueue));
        }

        for (String ignored : names) {
            assertThat(resQueue.take()).isEqualTo("null");
        }

        for (String ignored : names) {
            assertThat(serverReceivedNames.take()).isIn((Object[]) names);
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ParametersProvider.class)
    void testExceptionThrowingOnewayServiceSync(
            ClientOptions clientOptions, SerializationFormat format, SessionProtocol protocol)
            throws Exception {
        final OnewayHelloService.Iface client =
                Clients.builder(uri(Handlers.EXCEPTION_ONEWAY, format, protocol))
                       .options(clientOptions)
                       .build(Handlers.EXCEPTION_ONEWAY.iface());
        client.hello("kukuman");
        client.hello("kukuman2");
        assertThat(serverReceivedNames.take()).isEqualTo("kukuman");
        assertThat(serverReceivedNames.take()).isEqualTo("kukuman2");
    }

    @ParameterizedTest
    @ArgumentsSource(ParametersProvider.class)
    void testExceptionThrowingOnewayServiceAsync(
            ClientOptions clientOptions, SerializationFormat format, SessionProtocol protocol)
            throws Exception {
        final OnewayHelloService.AsyncIface client =
                Clients.builder(uri(Handlers.EXCEPTION_ONEWAY, format, protocol))
                       .options(clientOptions)
                       .build(Handlers.EXCEPTION_ONEWAY.asyncIface());
        final BlockingQueue<Object> resQueue = new LinkedBlockingQueue<>();

        final String[] names = { "kukuman", "kukuman2" };
        for (String name : names) {
            client.hello(name, new RequestQueuingCallback(resQueue));
        }

        for (String ignored : names) {
            assertThat(resQueue.take()).isEqualTo("null");
        }

        for (String ignored : names) {
            assertThat(serverReceivedNames.take()).isIn((Object[]) names);
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ParametersProvider.class)
    void testDevNullServiceSync(
            ClientOptions clientOptions, SerializationFormat format, SessionProtocol protocol)
            throws Exception {
        final DevNullService.Iface client =
                Clients.builder(uri(Handlers.DEVNULL, format, protocol))
                       .options(clientOptions)
                       .build(Handlers.DEVNULL.iface());
        client.consume("kukuman");
        client.consume("kukuman2");
        assertThat(serverReceivedNames.take()).isEqualTo("kukuman");
        assertThat(serverReceivedNames.take()).isEqualTo("kukuman2");
    }

    @ParameterizedTest
    @ArgumentsSource(ParametersProvider.class)
    void testDevNullServiceAsync(
            ClientOptions clientOptions, SerializationFormat format, SessionProtocol protocol)
            throws Exception {
        final DevNullService.AsyncIface client =
                Clients.builder(uri(Handlers.DEVNULL, format, protocol))
                       .options(clientOptions)
                       .build(Handlers.DEVNULL.asyncIface());
        final BlockingQueue<Object> resQueue = new LinkedBlockingQueue<>();

        final String[] names = { "kukuman", "kukuman2" };
        for (String name : names) {
            client.consume(name, new RequestQueuingCallback(resQueue));
        }

        for (String ignored : names) {
            assertThat(resQueue.take()).isEqualTo("null");
        }

        for (String ignored : names) {
            assertThat(serverReceivedNames.take()).isIn((Object[]) names);
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ParametersProvider.class)
    void testBinaryServiceSync(
            ClientOptions clientOptions, SerializationFormat format, SessionProtocol protocol)
            throws Exception {
        final BinaryService.Iface client = Clients.builder(uri(Handlers.BINARY, format, protocol))
                                                  .options(clientOptions)
                                                  .build(Handlers.BINARY.iface());

        final ByteBuffer result = client.process(ByteBuffer.wrap(new byte[] { 1, 2 }));
        final List<Byte> out = new ArrayList<>();
        for (int i = result.position(); i < result.limit(); i++) {
            out.add(result.get(i));
        }
        assertThat(out).containsExactly((byte) 2, (byte) 3);
    }

    @ParameterizedTest
    @ArgumentsSource(ParametersProvider.class)
    void testTimeServiceSync(
            ClientOptions clientOptions, SerializationFormat format, SessionProtocol protocol)
            throws Exception {
        final TimeService.Iface client =
                Clients.builder(uri(Handlers.TIME, format, protocol))
                       .options(clientOptions)
                       .build(Handlers.TIME.iface());

        final long serverTime = client.getServerTime();
        assertThat(serverTime).isLessThanOrEqualTo(System.currentTimeMillis());
    }

    @ParameterizedTest
    @ArgumentsSource(ParametersProvider.class)
    void testTimeServiceAsync(
            ClientOptions clientOptions, SerializationFormat format, SessionProtocol protocol)
            throws Exception {
        final TimeService.AsyncIface client =
                Clients.builder(uri(Handlers.TIME, format, protocol))
                       .options(clientOptions)
                       .build(Handlers.TIME.asyncIface());

        final BlockingQueue<Object> resQueue = new LinkedBlockingQueue<>();
        client.getServerTime(new RequestQueuingCallback(resQueue));

        final Object result = resQueue.take();
        assertThat(result).isInstanceOf(Long.class);
        assertThat((Long) result).isLessThanOrEqualTo(System.currentTimeMillis());
    }

    @ParameterizedTest
    @ArgumentsSource(ParametersProvider.class)
    void testFileServiceSync(
            ClientOptions clientOptions, SerializationFormat format, SessionProtocol protocol)
            throws Exception {
        final FileService.Iface client =
                Clients.builder(uri(Handlers.FILE, format, protocol))
                       .options(clientOptions)
                       .build(Handlers.FILE.iface());

        assertThatThrownBy(() -> client.create("test")).isInstanceOf(FileServiceException.class);
    }

    @ParameterizedTest
    @ArgumentsSource(ParametersProvider.class)
    void testFileServiceAsync(
            ClientOptions clientOptions, SerializationFormat format, SessionProtocol protocol)
            throws Exception {
        final FileService.AsyncIface client =
                Clients.builder(uri(Handlers.FILE, format, protocol))
                       .options(clientOptions)
                       .build(Handlers.FILE.asyncIface());

        final BlockingQueue<Object> resQueue = new LinkedBlockingQueue<>();
        client.create("test", new RequestQueuingCallback(resQueue));

        assertThat(resQueue.take()).isInstanceOf(FileServiceException.class);
    }

    @ParameterizedTest
    @ArgumentsSource(ParametersProvider.class)
    void testDerivedClient(
            ClientOptions clientOptions, SerializationFormat format, SessionProtocol protocol)
            throws Exception {
        final String AUTHORIZATION = "authorization";
        final String NO_TOKEN = "";
        final String TOKEN_A = "token 1234";
        final String TOKEN_B = "token 5678";

        final HeaderService.Iface client = Clients.builder(uri(Handlers.HEADER, format, protocol))
                                                  .options(clientOptions)
                                                  .build(Handlers.HEADER.iface());

        assertThat(client.header(AUTHORIZATION)).isEqualTo(NO_TOKEN);

        final HeaderService.Iface clientA =
                Clients.newDerivedClient(client,
                                         newHttpHeaderOption(HttpHeaderNames.of(AUTHORIZATION), TOKEN_A));

        final HeaderService.Iface clientB =
                Clients.newDerivedClient(client,
                                         newHttpHeaderOption(HttpHeaderNames.of(AUTHORIZATION), TOKEN_B));

        assertThat(clientA.header(AUTHORIZATION)).isEqualTo(TOKEN_A);
        assertThat(clientB.header(AUTHORIZATION)).isEqualTo(TOKEN_B);

        // Ensure that the parent client's HEADERS option did not change:
        assertThat(client.header(AUTHORIZATION)).isEqualTo(NO_TOKEN);
    }

    @ParameterizedTest
    @ArgumentsSource(ParametersProvider.class)
    void testMessageLogsForCall(
            ClientOptions clientOptions, SerializationFormat format, SessionProtocol protocol)
            throws Exception {
        final HelloService.Iface client = Clients.builder(uri(Handlers.HELLO, format, protocol))
                                                 .options(clientOptions)
                                                 .build(Handlers.HELLO.iface());
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

    @ParameterizedTest
    @ArgumentsSource(ParametersProvider.class)
    void testMessageLogsForOneWay(
            ClientOptions clientOptions, SerializationFormat format, SessionProtocol protocol)
            throws Exception {
        final OnewayHelloService.Iface client = Clients.builder(uri(Handlers.HELLO, format, protocol))
                                                       .options(clientOptions)
                                                       .build(Handlers.ONEWAYHELLO.iface());
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

    @ParameterizedTest
    @ArgumentsSource(ParametersProvider.class)
    void testMessageLogsForException(
            ClientOptions clientOptions, SerializationFormat format, SessionProtocol protocol)
            throws Exception {
        final HelloService.Iface client = Clients.builder(uri(Handlers.EXCEPTION, format, protocol))
                                                 .options(clientOptions)
                                                 .build(Handlers.EXCEPTION.iface());
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

    @ParameterizedTest
    @ArgumentsSource(ParametersProvider.class)
    void testBadStatus(
            ClientOptions clientOptions, SerializationFormat format, SessionProtocol protocol)
            throws Exception {
        final HelloService.Iface client = Clients.builder(server.uri(protocol, format) + "/500")
                                                 .options(clientOptions)
                                                 .build(Handlers.HELLO.iface());
        assertThatThrownBy(() -> client.hello(""))
                .isInstanceOfSatisfying(InvalidResponseHeadersException.class, cause -> {
                    assertThat(cause.headers().status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                })
                .hasMessageContaining(":status=500");
    }

    @ParameterizedTest
    @ArgumentsSource(ParametersProvider.class)
    void endpointMapping(
            ClientOptions clientOptions, SerializationFormat format, SessionProtocol protocol)
            throws Exception {

        final Endpoint group = Endpoint.of("127.0.0.1", protocol.isTls() ? server.httpsPort()
                                                                         : server.httpPort());
        final HelloService.Iface client =
                Clients.builder(format.uriText() + '+' + protocol.uriText() +
                                "://my-group/" + Handlers.HELLO.path(format))
                       .options(clientOptions)
                       .endpointRemapper(endpoint -> {
                           if ("my-group".equals(endpoint.host())) {
                               return group;
                           } else {
                               return endpoint;
                           }
                       })
                       .build(Handlers.HELLO.iface());

        assertThat(client.hello("trustin")).isEqualTo("Hello, trustin!");
    }

    private static URI uri(Handlers handler, SerializationFormat format,
                           SessionProtocol protocol) {
        return server.uri(protocol, format).resolve(handler.path(format));
    }

    private static ClientOptionValue<HttpHeaders> newHttpHeaderOption(AsciiString name, String value) {
        return ClientOptions.HEADERS.newValue(HttpHeaders.of(name, value));
    }

    private static class ParametersProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return getThriftSerializationFormats().stream().flatMap(serializationFormat -> Stream.of(
                            arguments(clientOptions.toBuilder()
                                                   .factory(clientFactoryWithUseHttp2Preface)
                                                   .build(),
                                      serializationFormat,
                                      SessionProtocol.HTTP),
                            arguments(clientOptions.toBuilder()
                                                   .factory(clientFactoryWithoutUseHttp2Preface)
                                                   .build(),
                                      serializationFormat,
                                      SessionProtocol.HTTP),
                            arguments(clientOptions.toBuilder()
                                                   .factory(clientFactoryWithoutUseHttp2Preface)
                                                   .build(),
                                      serializationFormat,
                                      SessionProtocol.HTTPS),
                            arguments(clientOptions.toBuilder()
                                                   .factory(clientFactoryWithoutUseHttp2Preface)
                                                   .build(),
                                      serializationFormat,
                                      SessionProtocol.H1),
                            arguments(clientOptions.toBuilder()
                                                   .factory(clientFactoryWithoutUseHttp2Preface)
                                                   .build(),
                                      serializationFormat,
                                      SessionProtocol.H1C),
                            arguments(clientOptions.toBuilder()
                                                   .factory(clientFactoryWithoutUseHttp2Preface)
                                                   .build(),
                                      serializationFormat,
                                      SessionProtocol.H2),
                            arguments(clientOptions.toBuilder()
                                                   .factory(clientFactoryWithUseHttp2Preface)
                                                   .build(),
                                      serializationFormat,
                                      SessionProtocol.H2C),
                            arguments(clientOptions.toBuilder()
                                                   .factory(clientFactoryWithoutUseHttp2Preface)
                                                   .build(),
                                      serializationFormat,
                                      SessionProtocol.H2C)
                    ));
        }
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
