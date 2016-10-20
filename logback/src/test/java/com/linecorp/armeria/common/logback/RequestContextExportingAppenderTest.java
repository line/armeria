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
package com.linecorp.armeria.common.logback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.AbstractMap.SimpleEntry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DefaultClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.ResponseLog;
import com.linecorp.armeria.common.logging.ResponseLogBuilder;
import com.linecorp.armeria.common.thrift.ThriftCall;
import com.linecorp.armeria.common.thrift.ThriftReply;
import com.linecorp.armeria.server.DefaultServiceRequestContext;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.status.StatusManager;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.util.AttributeKey;
import io.netty.util.internal.logging.InternalLoggerFactory;

public class RequestContextExportingAppenderTest {

    private static final AttributeKey<CustomValue> MY_ATTR =
            AttributeKey.valueOf(RequestContextExportingAppenderTest.class, "MY_ATTR");

    private static final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    private static final Logger rootLogger =
            (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private static final Logger logger =
            (Logger) LoggerFactory.getLogger(RequestContextExportingAppenderTest.class);

    @Rule
    public final TestName testName = new TestName();
    private Logger testLogger;

    @Before
    public void setUp() {
        rootLogger.getLoggerContext().getStatusManager().clear();
        MDC.clear();
        testLogger = (Logger) LoggerFactory.getLogger("loggerTest." + testName.getMethodName());
        testLogger.setLevel(Level.ALL);
    }

    @After
    public void tearDown() {
        final Logger logger = (Logger) LoggerFactory.getLogger(getClass());
        final StatusManager sm = rootLogger.getLoggerContext().getStatusManager();
        int count = 0;
        for (Status s : sm.getCopyOfStatusList()) {
            final int level = s.getEffectiveLevel();
            if (level == Status.INFO) {
                continue;
            }
            if (s.getMessage().contains(InternalLoggerFactory.class.getName())) {
                // Skip the warnings related with Netty.
                continue;
            }

            count++;
            switch (level) {
                case Status.WARN:
                    if (s.getThrowable() != null) {
                        logger.warn(s.getMessage(), s.getThrowable());
                    } else {
                        logger.warn(s.getMessage());
                    }
                    break;
                case Status.ERROR:
                    if (s.getThrowable() != null) {
                        logger.warn(s.getMessage(), s.getThrowable());
                    } else {
                        logger.warn(s.getMessage());
                    }
                    break;
            }
        }

        if (count > 0) {
            fail("Appender raised an exception.");
        }
    }

    @Test
    public void testMutabilityAndImmutability() {
        final AttributeKey<Object> someAttr =
                AttributeKey.valueOf(RequestContextExportingAppenderTest.class, "SOME_ATTR");
        final RequestContextExportingAppender a = new RequestContextExportingAppender();

        // Ensure mutability before start.
        a.addBuiltIn(BuiltInProperty.ELAPSED_NANOS);
        assertThat(a.getBuiltIns()).containsExactly(BuiltInProperty.ELAPSED_NANOS);

        a.addAttribute("some-attr", someAttr);
        assertThat(a.getAttributes()).containsOnlyKeys("some-attr")
                                     .containsValue(someAttr);

        a.addHttpRequestHeader(HttpHeaderNames.USER_AGENT);
        assertThat(a.getHttpRequestHeaders()).containsExactly(HttpHeaderNames.USER_AGENT);

        a.addHttpResponseHeader(HttpHeaderNames.SET_COOKIE);
        assertThat(a.getHttpResponseHeaders()).containsExactly(HttpHeaderNames.SET_COOKIE);

        final ListAppender<ILoggingEvent> la = new ListAppender<>();
        a.addAppender(la);
        a.start();
        la.start();

        // Ensure immutability after start.
        assertThatThrownBy(() -> a.addBuiltIn(BuiltInProperty.REQ_PATH))
                .isExactlyInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> a.addAttribute("my-attr", MY_ATTR))
                .isExactlyInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> a.addHttpRequestHeader(HttpHeaderNames.ACCEPT))
                .isExactlyInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> a.addHttpResponseHeader(HttpHeaderNames.DATE))
                .isExactlyInstanceOf(IllegalStateException.class);
    }

    @Test
    public void testXmlConfig() throws Exception {
        try {
            final JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            context.reset();

            configurator.doConfigure(getClass().getResource("testXmlConfig.xml"));

            final RequestContextExportingAppender rcia =
                    (RequestContextExportingAppender) logger.getAppender("RCIA");

            assertThat(rcia).isNotNull();
            assertThat(rcia.getBuiltIns()).containsExactly(BuiltInProperty.REMOTE_HOST);
            assertThat(rcia.getHttpRequestHeaders()).containsExactly(HttpHeaderNames.USER_AGENT);
            assertThat(rcia.getHttpResponseHeaders()).containsExactly(HttpHeaderNames.SET_COOKIE);

            final AttributeKey<Object> fooAttr = AttributeKey.valueOf("com.example.AttrKeys#FOO");
            final AttributeKey<Object> barAttr = AttributeKey.valueOf("com.example.AttrKeys#BAR");
            assertThat(rcia.getAttributes()).containsOnly(new SimpleEntry<>("foo", fooAttr),
                                                          new SimpleEntry<>("bar", barAttr));
        } finally {
            // Revert to the original configuration.
            final JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            context.reset();

            configurator.doConfigure(getClass().getResource("/logback-test.xml"));
        }
    }

    @Test
    public void testWithoutContext() {
        final List<ILoggingEvent> events = prepare();
        final ILoggingEvent e = log(events);
        assertThat(e.getMDCPropertyMap()).isEmpty();
    }

    @Test
    public void testMdcPropertyPreservation() throws Exception {
        final List<ILoggingEvent> events = prepare(a -> a.addBuiltIn(BuiltInProperty.REQ_DIRECTION));

        MDC.put("some-prop", "some-value");
        final ServiceRequestContext ctx = newServiceContext("/foo");
        try (RequestContext.PushHandle ignored = RequestContext.push(ctx)) {
            final ILoggingEvent e = log(events);
            final Map<String, String> mdc = e.getMDCPropertyMap();
            assertThat(mdc).containsEntry("req.direction", "INBOUND")
                           .containsEntry("some-prop", "some-value")
                           .hasSize(2);
        } finally {
            MDC.remove("some-prop");
        }
    }

    @Test
    public void testServiceContextWithoutLogs() throws Exception {
        final List<ILoggingEvent> events = prepare(a -> {
            // Export all properties.
            for (BuiltInProperty p : BuiltInProperty.values()) {
                a.addBuiltIn(p);
            }
        });

        final ServiceRequestContext ctx = newServiceContext("/foo");
        try (RequestContext.PushHandle ignored = RequestContext.push(ctx)) {
            final ILoggingEvent e = log(events);
            final Map<String, String> mdc = e.getMDCPropertyMap();
            assertThat(mdc).containsEntry("local.host", "server.com")
                           .containsEntry("local.ip", "5.6.7.8")
                           .containsEntry("local.port", "8080")
                           .containsEntry("remote.host", "client.com")
                           .containsEntry("remote.ip", "1.2.3.4")
                           .containsEntry("remote.port", "5678")
                           .containsEntry("req.direction", "INBOUND")
                           .containsEntry("req.authority", "server.com:8080")
                           .containsEntry("req.method", "GET")
                           .containsEntry("req.path", "/foo")
                           .containsEntry("scheme", "unknown+h2")
                           .containsEntry("tls.session_id", "0101020305080d15")
                           .containsEntry("tls.proto", "TLSv1.2")
                           .containsEntry("tls.cipher", "some-cipher")
                           .hasSize(14);
        }
    }

    @Test
    public void testServiceContextWithMinimalLogs() throws Exception {
        final List<ILoggingEvent> events = prepare(a -> {
            // Export all properties.
            for (BuiltInProperty p : BuiltInProperty.values()) {
                a.addBuiltIn(p);
            }
        });

        final ServiceRequestContext ctx = newServiceContext("/foo");
        try (RequestContext.PushHandle ignored = RequestContext.push(ctx)) {
            final RequestLogBuilder req = ctx.requestLogBuilder();
            final ResponseLogBuilder res = ctx.responseLogBuilder();
            req.end();
            res.end();

            final ILoggingEvent e = log(events);
            final Map<String, String> mdc = e.getMDCPropertyMap();
            assertThat(mdc).containsEntry("local.host", "server.com")
                           .containsEntry("local.ip", "5.6.7.8")
                           .containsEntry("local.port", "8080")
                           .containsEntry("remote.host", "client.com")
                           .containsEntry("remote.ip", "1.2.3.4")
                           .containsEntry("remote.port", "5678")
                           .containsEntry("req.direction", "INBOUND")
                           .containsEntry("req.authority", "some-host.server.com:8080")
                           .containsEntry("req.method", "GET")
                           .containsEntry("req.path", "/foo")
                           .containsEntry("scheme", "none+h2")
                           .containsEntry("req.content_length", "0")
                           .containsEntry("res.status_code", "0")
                           .containsEntry("res.content_length", "0")
                           .containsEntry("tls.session_id", "0101020305080d15")
                           .containsEntry("tls.proto", "TLSv1.2")
                           .containsEntry("tls.cipher", "some-cipher")
                           .containsKey("elapsed_nanos")
                           .hasSize(18);
        }
    }

    @Test
    public void testServiceContextWithFullLogs() throws Exception {
        final List<ILoggingEvent> events = prepare(a -> {
            // Export all properties.
            for (BuiltInProperty p : BuiltInProperty.values()) {
                a.addBuiltIn(p);
            }
            // .. and an attribute.
            a.addAttribute("my_attr", MY_ATTR, new CustomValueStringifier());
            // .. and some HTTP headers.
            a.addHttpRequestHeader(HttpHeaderNames.USER_AGENT);
            a.addHttpResponseHeader(HttpHeaderNames.DATE);
        });

        final ServiceRequestContext ctx = newServiceContext("/foo");
        try (RequestContext.PushHandle ignored = RequestContext.push(ctx)) {
            final RequestLogBuilder req = ctx.requestLogBuilder();
            final ResponseLogBuilder res = ctx.responseLogBuilder();
            req.serializationFormat(SerializationFormat.THRIFT_BINARY);
            req.contentLength(64);
            req.attr(RequestLog.HTTP_HEADERS).set(HttpHeaders.of(HttpHeaderNames.USER_AGENT, "some-client"));
            req.attr(RequestLog.RPC_REQUEST).set(new ThriftCall(1, Object.class, "hello", "world"));
            req.end();
            res.statusCode(200);
            res.contentLength(128);
            res.attr(ResponseLog.HTTP_HEADERS).set(HttpHeaders.of(HttpHeaderNames.DATE, "some-date"));
            res.attr(ResponseLog.RPC_RESPONSE).set(new ThriftReply(1, "Hello, world!"));
            res.end();

            final ILoggingEvent e = log(events);
            final Map<String, String> mdc = e.getMDCPropertyMap();
            assertThat(mdc).containsEntry("local.host", "server.com")
                           .containsEntry("local.ip", "5.6.7.8")
                           .containsEntry("local.port", "8080")
                           .containsEntry("remote.host", "client.com")
                           .containsEntry("remote.ip", "1.2.3.4")
                           .containsEntry("remote.port", "5678")
                           .containsEntry("req.direction", "INBOUND")
                           .containsEntry("req.authority", "some-host.server.com:8080")
                           .containsEntry("req.method", "GET")
                           .containsEntry("req.path", "/foo")
                           .containsEntry("scheme", "tbinary+h2")
                           .containsEntry("req.content_length", "64")
                           .containsEntry("req.rpc_method", "hello")
                           .containsEntry("req.rpc_params", "[world]")
                           .containsEntry("res.status_code", "200")
                           .containsEntry("res.content_length", "128")
                           .containsEntry("res.rpc_result", "Hello, world!")
                           .containsEntry("req.http_headers.user-agent", "some-client")
                           .containsEntry("res.http_headers.date", "some-date")
                           .containsEntry("tls.session_id", "0101020305080d15")
                           .containsEntry("tls.proto", "TLSv1.2")
                           .containsEntry("tls.cipher", "some-cipher")
                           .containsEntry("attrs.my_attr", "some-attr")
                           .containsKey("elapsed_nanos")
                           .hasSize(24);
        }
    }

    private ILoggingEvent log(List<ILoggingEvent> events) {
        final Integer value = ThreadLocalRandom.current().nextInt();
        testLogger.trace("{}", value);

        assertThat(events).hasSize(1);

        final ILoggingEvent event = events.remove(0);
        assertThat(event.getLevel()).isEqualTo(Level.TRACE);
        assertThat(event.getFormattedMessage()).isEqualTo(value.toString());
        assertThat(event.getArgumentArray()).containsExactly(value);
        return event;
    }

    private static ServiceRequestContext newServiceContext(String path) throws Exception {
        final Channel ch = mock(Channel.class);
        when(ch.remoteAddress()).thenReturn(
                new InetSocketAddress(InetAddress.getByAddress("client.com", new byte[] { 1, 2, 3, 4 }),
                                      5678));
        when(ch.localAddress()).thenReturn(
                new InetSocketAddress(InetAddress.getByAddress("server.com", new byte[] { 5, 6, 7, 8 }),
                                      8080));

        final Service<?, ?> service = mock(Service.class);
        final Server server = new ServerBuilder().withVirtualHost("some-host.server.com")
                                                 .serviceUnder("/", service)
                                                 .and().build();
        final ServiceConfig serviceConfig = server.config().findVirtualHost("some-host.server.com")
                                                  .serviceConfigs().get(0);
        final HttpRequest req = HttpRequest.of(HttpHeaders.of(HttpMethod.GET, path)
                                                          .authority("server.com:8080"));

        final ServiceRequestContext ctx = new DefaultServiceRequestContext(
                serviceConfig,
                ch, SessionProtocol.H2, req.method().name(), req.path(), req.path(),
                req, newSslSession());

        ctx.attr(MY_ATTR).set(new CustomValue("some-attr"));
        return ctx;
    }

    @Test
    public void testClientContextWithMinimalLogs() throws Exception {
        final List<ILoggingEvent> events = prepare(a -> {
            // Export all properties.
            for (BuiltInProperty p : BuiltInProperty.values()) {
                a.addBuiltIn(p);
            }
        });

        final ClientRequestContext ctx = newClientContext("/foo");
        try (RequestContext.PushHandle ignored = RequestContext.push(ctx)) {
            final ILoggingEvent e = log(events);
            final Map<String, String> mdc = e.getMDCPropertyMap();
            assertThat(mdc).containsEntry("local.host", "client.com")
                           .containsEntry("local.ip", "5.6.7.8")
                           .containsEntry("local.port", "5678")
                           .containsEntry("remote.host", "server.com")
                           .containsEntry("remote.ip", "1.2.3.4")
                           .containsEntry("remote.port", "8080")
                           .containsEntry("req.direction", "OUTBOUND")
                           .containsEntry("req.authority", "server.com:8080")
                           .containsEntry("req.method", "GET")
                           .containsEntry("req.path", "/foo")
                           .containsEntry("scheme", "unknown+h2")
                           .containsEntry("tls.session_id", "0101020305080d15")
                           .containsEntry("tls.proto", "TLSv1.2")
                           .containsEntry("tls.cipher", "some-cipher")
                           .hasSize(14);
        }
    }

    @Test
    public void testClientContextWithFullLogs() throws Exception {
        final List<ILoggingEvent> events = prepare(a -> {
            // Export all properties.
            for (BuiltInProperty p : BuiltInProperty.values()) {
                a.addBuiltIn(p);
            }
            // .. and an attribute.
            a.addAttribute("my_attr", MY_ATTR, new CustomValueStringifier());
            // .. and some HTTP headers.
            a.addHttpRequestHeader(HttpHeaderNames.USER_AGENT);
            a.addHttpResponseHeader(HttpHeaderNames.DATE);
        });

        final ClientRequestContext ctx = newClientContext("/bar");
        try (RequestContext.PushHandle ignored = RequestContext.push(ctx)) {
            final RequestLogBuilder req = ctx.requestLogBuilder();
            final ResponseLogBuilder res = ctx.responseLogBuilder();
            req.serializationFormat(SerializationFormat.THRIFT_BINARY);
            req.contentLength(64);
            req.attr(RequestLog.HTTP_HEADERS).set(HttpHeaders.of(HttpHeaderNames.USER_AGENT, "some-client"));
            req.attr(RequestLog.RPC_REQUEST).set(new ThriftCall(1, Object.class, "hello", "world"));
            req.end();
            res.statusCode(200);
            res.contentLength(128);
            res.attr(ResponseLog.HTTP_HEADERS).set(HttpHeaders.of(HttpHeaderNames.DATE, "some-date"));
            res.attr(ResponseLog.RPC_RESPONSE).set(new ThriftReply(1, "Hello, world!"));
            res.end();

            final ILoggingEvent e = log(events);
            final Map<String, String> mdc = e.getMDCPropertyMap();
            assertThat(mdc).containsEntry("local.host", "client.com")
                           .containsEntry("local.ip", "5.6.7.8")
                           .containsEntry("local.port", "5678")
                           .containsEntry("remote.host", "server.com")
                           .containsEntry("remote.ip", "1.2.3.4")
                           .containsEntry("remote.port", "8080")
                           .containsEntry("req.direction", "OUTBOUND")
                           .containsEntry("req.authority", "some-host.server.com:8080")
                           .containsEntry("req.method", "GET")
                           .containsEntry("req.path", "/bar")
                           .containsEntry("scheme", "tbinary+h2")
                           .containsEntry("req.content_length", "64")
                           .containsEntry("req.rpc_method", "hello")
                           .containsEntry("req.rpc_params", "[world]")
                           .containsEntry("res.status_code", "200")
                           .containsEntry("res.content_length", "128")
                           .containsEntry("res.rpc_result", "Hello, world!")
                           .containsEntry("req.http_headers.user-agent", "some-client")
                           .containsEntry("res.http_headers.date", "some-date")
                           .containsEntry("tls.session_id", "0101020305080d15")
                           .containsEntry("tls.proto", "TLSv1.2")
                           .containsEntry("tls.cipher", "some-cipher")
                           .containsEntry("attrs.my_attr", "some-attr")
                           .containsKey("elapsed_nanos")
                           .hasSize(24);
        }
    }

    private static ClientRequestContext newClientContext(String path) throws Exception {
        final Channel ch = mock(Channel.class);
        when(ch.remoteAddress()).thenReturn(
                new InetSocketAddress(InetAddress.getByAddress("server.com", new byte[] { 1, 2, 3, 4 }),
                                      8080));
        when(ch.localAddress()).thenReturn(
                new InetSocketAddress(InetAddress.getByAddress("client.com", new byte[] { 5, 6, 7, 8 }),
                                      5678));

        final HttpRequest req = HttpRequest.of(HttpHeaders.of(HttpMethod.GET, path)
                                                          .authority("server.com:8080"));

        final DefaultClientRequestContext ctx = new DefaultClientRequestContext(
                mock(EventLoop.class), SessionProtocol.H2,
                Endpoint.of("server.com", 8080),
                req.method().name(), req.path(), "",
                ClientOptions.DEFAULT, req) {

            @Nullable
            @Override
            public SSLSession sslSession() {
                return newSslSession();
            }
        };

        ctx.requestLogBuilder().start(
                ch, ctx.sessionProtocol(), "some-host.server.com", ctx.method(), ctx.path());

        ctx.attr(MY_ATTR).set(new CustomValue("some-attr"));
        return ctx;
    }

    private static SSLSession newSslSession() {
        final SSLSession sslSession = mock(SSLSession.class);
        when(sslSession.getId()).thenReturn(new byte[] { 1, 1, 2, 3, 5, 8, 13, 21 });
        when(sslSession.getProtocol()).thenReturn("TLSv1.2");
        when(sslSession.getCipherSuite()).thenReturn("some-cipher");
        return sslSession;
    }

    @SafeVarargs
    private final List<ILoggingEvent> prepare(Consumer<RequestContextExportingAppender>... configurators) {
        final RequestContextExportingAppender a = new RequestContextExportingAppender();
        for (Consumer<RequestContextExportingAppender> c : configurators) {
            c.accept(a);
        }

        final ListAppender<ILoggingEvent> la = new ListAppender<>();
        a.addAppender(la);
        a.start();
        la.start();
        testLogger.addAppender(a);
        return la.list;
    }
}
