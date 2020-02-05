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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.base.Strings;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.thrift.ThriftCall;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;
import com.linecorp.armeria.common.thrift.ThriftReply;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.service.test.thrift.main.HelloService.AsyncIface;
import com.linecorp.armeria.service.test.thrift.main.OnewayHelloService;
import com.linecorp.armeria.service.test.thrift.main.SleepService;

public abstract class AbstractThriftOverHttpTest {

    private static final String LARGER_THAN_TLS = Strings.repeat("A", 16384);

    private static final Server server;

    private static int httpPort;
    private static int httpsPort;

    private static volatile boolean recordMessageLogs;
    private static final BlockingQueue<RequestLog> requestLogs = new LinkedBlockingQueue<>();

    abstract static class HelloServiceBase implements AsyncIface {
        @Override
        @SuppressWarnings("unchecked")
        public void hello(String name, AsyncMethodCallback resultHandler) throws TException {
            resultHandler.onComplete(getResponse(name));
        }

        protected String getResponse(String name) {
            return "Hello, " + name + '!';
        }
    }

    static class HelloServiceChild extends HelloServiceBase {
        @Override
        protected String getResponse(String name) {
            return "Goodbye, " + name + '!';
        }
    }

    static {
        final ServerBuilder sb = Server.builder();
        try {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();

            sb.service("/hello", THttpService.of(
                    (AsyncIface) (name, resultHandler) -> resultHandler.onComplete("Hello, " + name + '!')));

            sb.service("/hellochild", THttpService.of(new HelloServiceChild()));

            sb.service("/hello_oneway_sync", THttpService.of(
                    (OnewayHelloService.Iface) name -> {
                        if (!"success".equals(name)) {
                            throw new AnticipatedException("expected 'success'");
                        }
                    }));

            sb.service("/hello_oneway_async", THttpService.of(
                    (OnewayHelloService.AsyncIface) (name, resultHandler) -> {
                        if ("success".equals(name)) {
                            resultHandler.onComplete(null);
                        } else {
                            resultHandler.onError(new AnticipatedException("expected 'success'"));
                        }
                    }));

            sb.service("/exception", THttpService.of(
                    (AsyncIface) (name, resultHandler) ->
                            resultHandler.onError(new AnticipatedException(name))));

            sb.service("/sleep", THttpService.of(
                    (SleepService.AsyncIface) (milliseconds, resultHandler) ->
                            ServiceRequestContext.current().eventLoop().schedule(
                                    () -> resultHandler.onComplete(milliseconds),
                                    milliseconds, TimeUnit.MILLISECONDS)));

            // Response larger than a h1 TLS record
            sb.service("/large", THttpService.of(
                    (AsyncIface) (name, resultHandler) -> resultHandler.onComplete(LARGER_THAN_TLS)));

            sb.decorator(LoggingService.newDecorator());

            final Function<? super HttpService, ? extends HttpService> logCollectingDecorator =
                    s -> new SimpleDecoratingHttpService(s) {
                        @Override
                        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                            if (recordMessageLogs) {
                                ctx.log().whenComplete().thenAccept(requestLogs::add);
                            }
                            return delegate().serve(ctx, req);
                        }
                    };

            sb.decorator(logCollectingDecorator);
        } catch (Exception e) {
            throw new Error(e);
        }
        server = sb.build();
    }

    @BeforeClass
    public static void init() throws Exception {
        server.start().get();

        httpPort = server.activePorts().values().stream()
                         .filter(ServerPort::hasHttp).findAny().get()
                         .localAddress().getPort();
        httpsPort = server.activePorts().values().stream()
                          .filter(ServerPort::hasHttps).findAny().get()
                          .localAddress().getPort();
    }

    @AfterClass
    public static void destroy() throws Exception {
        server.stop().get();
    }

    @Before
    public void beforeTest() {
        recordMessageLogs = false;
        requestLogs.clear();
    }

    @Test
    public void testHttpInvocation() throws Exception {
        try (TTransport transport = newTransport("http", "/hello")) {
            final HelloService.Client client =
                    new HelloService.Client.Factory().getClient(
                            ThriftProtocolFactories.BINARY.getProtocol(transport));

            assertThat(client.hello("Trustin")).isEqualTo("Hello, Trustin!");
        }
    }

    @Test
    public void testInheritedThriftService() throws Exception {
        try (TTransport transport = newTransport("http", "/hellochild")) {
            final HelloService.Client client =
                    new HelloService.Client.Factory().getClient(
                            ThriftProtocolFactories.BINARY.getProtocol(transport));

            assertThat(client.hello("Trustin")).isEqualTo("Goodbye, Trustin!");
        }
    }

    @Test
    public void testOnewaySyncInvocation() throws Exception {
        recordMessageLogs = true;

        try (TTransport transport = newTransport("http", "/hello_oneway_sync")) {
            final OnewayHelloService.Client client =
                    new OnewayHelloService.Client.Factory().getClient(
                            ThriftProtocolFactories.BINARY.getProtocol(transport));
            // Success
            client.hello("success");
            verifyOneWayInvocation(OnewayHelloService.Iface.class, "success");
        }

        try (TTransport transport = newTransport("http", "/hello_oneway_sync")) {
            final OnewayHelloService.Client client =
                    new OnewayHelloService.Client.Factory().getClient(
                            ThriftProtocolFactories.BINARY.getProtocol(transport));
            // Failure
            client.hello("failure");
            verifyOneWayInvocation(OnewayHelloService.Iface.class, "failure");
        }
    }

    @Test
    public void testOnewayAsyncInvocation() throws Exception {
        recordMessageLogs = true;

        try (TTransport transport = newTransport("http", "/hello_oneway_async")) {
            final OnewayHelloService.Client client =
                    new OnewayHelloService.Client.Factory().getClient(
                            ThriftProtocolFactories.BINARY.getProtocol(transport));
            // Success
            client.hello("success");
            verifyOneWayInvocation(OnewayHelloService.AsyncIface.class, "success");
        }

        try (TTransport transport = newTransport("http", "/hello_oneway_async")) {
            final OnewayHelloService.Client client =
                    new OnewayHelloService.Client.Factory().getClient(
                            ThriftProtocolFactories.BINARY.getProtocol(transport));
            // Failure
            client.hello("failure");
            verifyOneWayInvocation(OnewayHelloService.AsyncIface.class, "failure");
        }
    }

    private static void verifyOneWayInvocation(Class<?> expectedServiceType, String expectedParam) {
        await().untilAsserted(() -> {
            final RequestLog log = requestLogs.poll();
            assertThat(log).isNotNull();
            final RpcRequest req = (RpcRequest) log.requestContent();
            final RpcResponse res = (RpcResponse) log.responseContent();
            assertThat(req).isNotNull();
            assertThat(req.serviceType()).isSameAs(expectedServiceType);
            assertThat(req.method()).isEqualTo("hello");
            assertThat(req.params()).containsExactly(expectedParam);
            assertThat((Object) res).isNotNull();
            assertThat(res.get()).isNull();
        });
    }

    @Test
    public void testHttpsInvocation() throws Exception {
        try (TTransport transport = newTransport("https", "/hello")) {
            final HelloService.Client client =
                    new HelloService.Client.Factory().getClient(
                            ThriftProtocolFactories.BINARY.getProtocol(transport));

            assertThat(client.hello("Trustin")).isEqualTo("Hello, Trustin!");
        }
    }

    @Test
    public void testLargeHttpsInvocation() throws Exception {
        try (TTransport transport = newTransport("https", "/large")) {
            final HelloService.Client client =
                    new HelloService.Client.Factory().getClient(
                            ThriftProtocolFactories.BINARY.getProtocol(transport));

            assertThat(client.hello("Trustin")).isEqualTo(LARGER_THAN_TLS);
        }
    }

    @Test
    public void testAcceptHeaderWithCommaSeparatedMediaTypes() throws Exception {
        try (TTransport transport = newTransport("http", "/hello",
                                                 HttpHeaders.of(HttpHeaderNames.ACCEPT, "text/plain, */*"))) {
            final HelloService.Client client =
                    new HelloService.Client.Factory().getClient(
                            ThriftProtocolFactories.BINARY.getProtocol(transport));

            assertThat(client.hello("Trustin")).isEqualTo("Hello, Trustin!");
        }
    }

    @Test
    public void testAcceptHeaderWithQValues() throws Exception {
        // Server should choose TBINARY because it has higher q-value (0.5) than that of TTEXT (0.2)
        try (TTransport transport = newTransport(
                "http", "/hello",
                HttpHeaders.of(HttpHeaderNames.ACCEPT,
                               "application/x-thrift; protocol=TTEXT; q=0.2, " +
                               "application/x-thrift; protocol=TBINARY; q=0.5"))) {
            final HelloService.Client client =
                    new HelloService.Client.Factory().getClient(
                            ThriftProtocolFactories.BINARY.getProtocol(transport));

            assertThat(client.hello("Trustin")).isEqualTo("Hello, Trustin!");
        }
    }

    @Test
    public void testAcceptHeaderWithDefaultQValues() throws Exception {
        // Server should choose TBINARY because it has higher q-value (default 1.0) than that of TTEXT (0.2)
        try (TTransport transport = newTransport(
                "http", "/hello",
                HttpHeaders.of(HttpHeaderNames.ACCEPT,
                               "application/x-thrift; protocol=TTEXT; q=0.2, " +
                               "application/x-thrift; protocol=TBINARY"))) {
            final HelloService.Client client =
                    new HelloService.Client.Factory().getClient(
                            ThriftProtocolFactories.BINARY.getProtocol(transport));

            assertThat(client.hello("Trustin")).isEqualTo("Hello, Trustin!");
        }
    }

    @Test
    public void testAcceptHeaderWithUnsupportedMediaTypes() throws Exception {
        // Server should choose TBINARY because it does not support the media type
        // with the highest preference (text/plain).
        try (TTransport transport = newTransport(
                "http", "/hello",
                HttpHeaders.of(HttpHeaderNames.ACCEPT,
                               "application/x-thrift; protocol=TBINARY; q=0.2, text/plain"))) {
            final HelloService.Client client =
                    new HelloService.Client.Factory().getClient(
                            ThriftProtocolFactories.BINARY.getProtocol(transport));

            assertThat(client.hello("Trustin")).isEqualTo("Hello, Trustin!");
        }
    }

    @Test(timeout = 10000)
    public void testMessageLogsForCall() throws Exception {
        try (TTransport transport = newTransport("http", "/hello")) {
            final HelloService.Client client =
                    new HelloService.Client.Factory().getClient(
                            ThriftProtocolFactories.BINARY.getProtocol(transport));
            recordMessageLogs = true;
            client.hello("Trustin");
        }

        final RequestLog log = takeLog();

        assertThat(log.requestHeaders()).isInstanceOf(HttpHeaders.class);
        assertThat(log.requestContent()).isInstanceOf(RpcRequest.class);
        assertThat(log.rawRequestContent()).isInstanceOf(ThriftCall.class);

        final RpcRequest request = (RpcRequest) log.requestContent();
        assertThat(request.serviceType()).isEqualTo(HelloService.AsyncIface.class);
        assertThat(request.method()).isEqualTo("hello");
        assertThat(request.params()).containsExactly("Trustin");

        final ThriftCall rawRequest = (ThriftCall) log.rawRequestContent();
        assertThat(rawRequest.header().type).isEqualTo(TMessageType.CALL);
        assertThat(rawRequest.header().name).isEqualTo("hello");
        assertThat(rawRequest.args()).isInstanceOf(HelloService.hello_args.class);
        assertThat(((HelloService.hello_args) rawRequest.args()).getName()).isEqualTo("Trustin");

        assertThat(log.responseHeaders()).isInstanceOf(HttpHeaders.class);
        assertThat(log.responseContent()).isInstanceOf(RpcResponse.class);
        assertThat(log.rawResponseContent()).isInstanceOf(ThriftReply.class);

        final RpcResponse response = (RpcResponse) log.responseContent();
        assertThat(response.get()).isEqualTo("Hello, Trustin!");

        final ThriftReply rawResponse = (ThriftReply) log.rawResponseContent();
        assertThat(rawResponse.header().type).isEqualTo(TMessageType.REPLY);
        assertThat(rawResponse.header().name).isEqualTo("hello");
        assertThat(rawResponse.result()).isInstanceOf(HelloService.hello_result.class);
        assertThat(((HelloService.hello_result) rawResponse.result()).getSuccess())
                .isEqualTo("Hello, Trustin!");
    }

    @Test(timeout = 10000)
    public void testMessageLogsForException() throws Exception {
        try (TTransport transport = newTransport("http", "/exception")) {
            final HelloService.Client client =
                    new HelloService.Client.Factory().getClient(
                            ThriftProtocolFactories.BINARY.getProtocol(transport));
            recordMessageLogs = true;
            assertThatThrownBy(() -> client.hello("Trustin")).isInstanceOf(TApplicationException.class);
        }

        final RequestLog log = takeLog();

        assertThat(log.requestHeaders()).isInstanceOf(HttpHeaders.class);
        assertThat(log.requestContent()).isInstanceOf(RpcRequest.class);
        assertThat(log.rawRequestContent()).isInstanceOf(ThriftCall.class);

        final RpcRequest request = (RpcRequest) log.requestContent();
        assertThat(request.serviceType()).isEqualTo(HelloService.AsyncIface.class);
        assertThat(request.method()).isEqualTo("hello");
        assertThat(request.params()).containsExactly("Trustin");

        final ThriftCall rawRequest = (ThriftCall) log.rawRequestContent();
        assertThat(rawRequest.header().type).isEqualTo(TMessageType.CALL);
        assertThat(rawRequest.header().name).isEqualTo("hello");
        assertThat(rawRequest.args()).isInstanceOf(HelloService.hello_args.class);
        assertThat(((HelloService.hello_args) rawRequest.args()).getName()).isEqualTo("Trustin");

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

    protected final TTransport newTransport(String scheme, String path) throws TTransportException {
        return newTransport(scheme, path, HttpHeaders.of());
    }

    protected final TTransport newTransport(String scheme, String path,
                                            HttpHeaders headers) throws TTransportException {
        return newTransport(newUri(scheme, path), headers);
    }

    protected abstract TTransport newTransport(String uri, HttpHeaders headers) throws TTransportException;

    protected static String newUri(String scheme, String path) {
        switch (scheme) {
            case "http":
                return scheme + "://127.0.0.1:" + httpPort + path;
            case "https":
                return scheme + "://127.0.0.1:" + httpsPort + path;
        }

        throw new Error();
    }

    private static RequestLog takeLog() throws InterruptedException {
        for (;;) {
            final RequestLog log = requestLogs.take();
            if (log.requestHeaders().method() == HttpMethod.HEAD) {
                // Skip the upgrade request.
                continue;
            }

            return log;
        }
    }
}
