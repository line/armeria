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
package com.linecorp.armeria.common.logback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.logback.HelloService.hello_args;
import com.linecorp.armeria.common.logback.HelloService.hello_result;
import com.linecorp.armeria.common.logging.BuiltInProperty;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.thrift.ThriftCall;
import com.linecorp.armeria.common.thrift.ThriftReply;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ProxiedAddresses;
import com.linecorp.armeria.server.ServiceRequestContext;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.status.StatusManager;
import io.netty.util.AttributeKey;
import io.netty.util.internal.logging.InternalLoggerFactory;

class RequestContextExportingAppenderTest {

    private static final RpcRequest RPC_REQ = RpcRequest.of(Object.class, "hello", "world");
    private static final RpcResponse RPC_RES = RpcResponse.of("Hello, world!");
    private static final ThriftCall THRIFT_CALL =
            new ThriftCall(new TMessage("hello", TMessageType.CALL, 1),
                           new hello_args("world"));
    private static final ThriftReply THRIFT_REPLY =
            new ThriftReply(new TMessage("hello", TMessageType.REPLY, 1),
                            new hello_result("Hello, world!"));

    private static final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    private static final Logger rootLogger =
            (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    private Logger testLogger;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        rootLogger.getLoggerContext().getStatusManager().clear();
        MDC.clear();
        testLogger = (Logger) LoggerFactory.getLogger("loggerTest." + testInfo.getDisplayName());
        testLogger.setLevel(Level.ALL);
    }

    @AfterEach
    void tearDown() {
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
    void testMutabilityAndImmutability() {
        final AttributeKey<Object> someAttr =
                AttributeKey.valueOf(RequestContextExportingAppenderTest.class, "SOME_ATTR");
        final RequestContextExportingAppender a = new RequestContextExportingAppender();

        // Ensure mutability before start.
        a.addBuiltIn(BuiltInProperty.ELAPSED_NANOS);
        a.addAttribute("some-attr", someAttr);
        a.addRequestHeader(HttpHeaderNames.USER_AGENT);
        a.addResponseHeader(HttpHeaderNames.SET_COOKIE);

        final ListAppender<ILoggingEvent> la = new ListAppender<>();
        a.addAppender(la);
        a.start();
        la.start();

        // Ensure immutability after start.
        assertThatThrownBy(() -> a.addBuiltIn(BuiltInProperty.REQ_PATH))
                .isExactlyInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> a.addAttribute("my-attr", CustomObject.ATTR))
                .isExactlyInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> a.addRequestHeader(HttpHeaderNames.ACCEPT))
                .isExactlyInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> a.addResponseHeader(HttpHeaderNames.DATE))
                .isExactlyInstanceOf(IllegalStateException.class);
    }

    @Test
    void testXmlConfig() throws Exception {
        try {
            final Logger logger = (Logger) LoggerFactory.getLogger("RCEA");

            final JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            context.reset();

            configurator.doConfigure(getClass().getResource("testXmlConfig.xml"));

            final RequestContextExportingAppender rcea =
                    (RequestContextExportingAppender) logger.getAppender("RCEA");

            rcea.start();
            logger.trace("foo");

            final ServiceRequestContext ctx = newServiceContext("/foo", "name=alice");
            final RequestLogBuilder log = ctx.logBuilder();
            log.endRequest();
            log.endResponse();

            final Map<String, String> mdc = rcea.exporter().export(ctx);
            assertThat(mdc).containsEntry("local.host", "server.com")
                           .containsEntry("local.ip", "5.6.7.8")
                           .containsEntry("local.port", "8080")
                           .containsEntry("foo.remote.host", "client.com")
                           .containsEntry("remote.host", "client.com")
                           .containsEntry("remote.ip", "1.2.3.4")
                           .containsEntry("remote.port", "5678")
                           .containsEntry("req.headers.user-agent", "some-client")
                           .containsEntry("attrs.foo", "hello")
                           .containsEntry("attrs.bar", "some-name")
                           .containsEntry("baz", "some-value")
                           .hasSize(11);
        } finally {
            // Revert to the original configuration.
            final JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            context.reset();

            configurator.doConfigure(getClass().getResource("/logback-test.xml"));
        }
    }

    @Test
    void testXmlConfigExportPrefix() throws Exception {
        try {
            final Logger logger = (Logger) LoggerFactory.getLogger("RCEA_WITH_PREFIX");

            final JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            context.reset();

            configurator.doConfigure(getClass().getResource("testXmlConfig.xml"));

            final RequestContextExportingAppender rcea =
                    (RequestContextExportingAppender) logger.getAppender("RCEA_WITH_PREFIX");

            rcea.start();
            logger.trace("foo");

            final ServiceRequestContext ctx = newServiceContext("/foo", "name=alice");
            final RequestLogBuilder log = ctx.logBuilder();
            log.endRequest();
            log.endResponse();

            final Map<String, String> mdc = rcea.exporter().export(ctx);
            assertThat(mdc).containsEntry("defaultPrefix.remote.host", "client.com")
                           .containsEntry("defaultPrefix.remote.ip", "1.2.3.4")
                           .containsEntry("defaultPrefix.remote.port", "5678")
                           .containsEntry("group1.remote.host", "client.com")
                           .containsEntry("group1.remote.ip", "1.2.3.4")
                           .containsEntry("group1.remote.port", "5678")
                           .containsEntry("group2.remote.host", "client.com")
                           .containsEntry("group2.remote.ip", "1.2.3.4")
                           .containsEntry("group2.remote.port", "5678")
                           .containsEntry("remote.host", "client.com")
                           .containsEntry("remote.ip", "1.2.3.4")
                           .containsEntry("remote.port", "5678")
                           .hasSize(12);
        } finally {
            // Revert to the original configuration.
            final JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            context.reset();

            configurator.doConfigure(getClass().getResource("/logback-test.xml"));
        }
    }

    @Test
    void testMultipleExporters() throws Exception {
        try {
            final Logger logger = (Logger) LoggerFactory.getLogger("multiple-exporters");

            final JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            context.reset();

            configurator.doConfigure(getClass().getResource("testXmlConfig.xml"));

            final RequestContextExportingAppender rcea1 =
                    (RequestContextExportingAppender) logger.getAppender("RCEA1");
            final RequestContextExportingAppender rcea2 =
                    (RequestContextExportingAppender) logger.getAppender("RCEA2");

            rcea1.start();
            rcea2.start();
            logger.debug("foo");

            final ServiceRequestContext ctx = newServiceContext("/foo", "name=alice");
            final RequestLogBuilder log = ctx.logBuilder();
            log.endRequest();
            log.endResponse();

            assertThat(rcea1.exporter().export(ctx)).containsOnlyKeys("remote.ip");
            assertThat(rcea2.exporter().export(ctx)).containsOnlyKeys("remote.port");
        } finally {
            // Revert to the original configuration.
            final JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            context.reset();

            configurator.doConfigure(getClass().getResource("/logback-test.xml"));
        }
    }

    @Test
    void testWithoutContext() {
        final List<ILoggingEvent> events = prepare();
        final ILoggingEvent e = log(events);
        assertThat(e.getMDCPropertyMap()).isEmpty();
    }

    @Test
    void testMdcPropertyPreservation() throws Exception {
        final List<ILoggingEvent> events = prepare(a -> a.addBuiltIn(BuiltInProperty.REQ_DIRECTION));

        MDC.put("some-prop", "some-value");
        final ServiceRequestContext ctx = newServiceContext("/foo", null);
        try (SafeCloseable ignored = ctx.push()) {
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
    void testServiceContextWithoutLogs() throws Exception {
        final List<ILoggingEvent> events = prepare(a -> {
            // Export all properties.
            for (BuiltInProperty p : BuiltInProperty.values()) {
                a.addBuiltIn(p);
            }
        });

        final ServiceRequestContext ctx = newServiceContext("/foo", null);
        try (SafeCloseable ignored = ctx.push()) {
            final ILoggingEvent e = log(events);
            final Map<String, String> mdc = e.getMDCPropertyMap();
            assertThat(mdc).containsEntry("local.host", "server.com")
                           .containsEntry("local.ip", "5.6.7.8")
                           .containsEntry("local.port", "8080")
                           .containsEntry("remote.host", "client.com")
                           .containsEntry("remote.ip", "1.2.3.4")
                           .containsEntry("remote.port", "5678")
                           .containsEntry("client.ip", "9.10.11.12")
                           .containsEntry("req.direction", "INBOUND")
                           .containsEntry("req.authority", "server.com:8080")
                           .containsEntry("req.method", "GET")
                           .containsEntry("req.path", "/foo")
                           .containsEntry("scheme", "unknown+h2")
                           .containsEntry("tls.session_id", "0101020305080d15")
                           .containsEntry("tls.proto", "TLSv1.2")
                           .containsEntry("tls.cipher", "some-cipher")
                           .containsKey("req.id")
                           .containsKey("req.root_id")
                           .hasSize(17);
        }
    }

    @Test
    void testServiceContextWithMinimalLogs() throws Exception {
        final List<ILoggingEvent> events = prepare(a -> {
            // Export all properties.
            for (BuiltInProperty p : BuiltInProperty.values()) {
                a.addBuiltIn(p);
            }
        });

        final ServiceRequestContext ctx = newServiceContext("/foo", "name=alice");
        try (SafeCloseable ignored = ctx.push()) {
            final RequestLogBuilder log = ctx.logBuilder();
            log.endRequest();
            log.endResponse();

            final ILoggingEvent e = log(events);
            final Map<String, String> mdc = e.getMDCPropertyMap();
            assertThat(mdc).containsEntry("local.host", "server.com")
                           .containsEntry("local.ip", "5.6.7.8")
                           .containsEntry("local.port", "8080")
                           .containsEntry("remote.host", "client.com")
                           .containsEntry("remote.ip", "1.2.3.4")
                           .containsEntry("remote.port", "5678")
                           .containsEntry("client.ip", "9.10.11.12")
                           .containsEntry("req.direction", "INBOUND")
                           .containsEntry("req.authority", "server.com:8080")
                           .containsEntry("req.name", "GET")
                           .containsEntry("req.service_name", ctx.config().service().getClass().getName())
                           .containsEntry("req.method", "GET")
                           .containsEntry("req.path", "/foo")
                           .containsEntry("req.query", "name=alice")
                           .containsEntry("scheme", "none+h2")
                           .containsEntry("req.content_length", "0")
                           .containsEntry("res.status_code", "0")
                           .containsEntry("res.content_length", "0")
                           .containsEntry("tls.session_id", "0101020305080d15")
                           .containsEntry("tls.proto", "TLSv1.2")
                           .containsEntry("tls.cipher", "some-cipher")
                           .containsKey("elapsed_nanos")
                           .containsKey("req.id")
                           .containsKey("req.root_id")
                           .hasSize(24);
        }
    }

    @Test
    void testServiceContextWithFullLogs() throws Exception {
        final List<ILoggingEvent> events = prepare(a -> {
            // Export all properties.
            for (BuiltInProperty p : BuiltInProperty.values()) {
                a.addBuiltIn(p);
            }
            // .. and an attribute.
            a.addAttribute("attrs.my_attr_name", CustomObject.ATTR, new CustomObjectNameStringifier());
            a.addAttribute("attrs.my_attr_value", CustomObject.ATTR, new CustomObjectValueStringifier());
            // .. and some HTTP headers.
            a.addRequestHeader(HttpHeaderNames.USER_AGENT);
            a.addResponseHeader(HttpHeaderNames.DATE);
        });

        final ServiceRequestContext ctx = newServiceContext("/foo", "bar=baz");
        try (SafeCloseable ignored = ctx.push()) {
            final RequestLogBuilder log = ctx.logBuilder();
            log.serializationFormat(ThriftSerializationFormats.BINARY);
            log.requestLength(64);
            log.requestHeaders(RequestHeaders.of(HttpMethod.GET, "/foo?bar=baz",
                                                 HttpHeaderNames.USER_AGENT, "some-client"));
            log.requestContent(RPC_REQ, THRIFT_CALL);
            log.endRequest();
            log.responseLength(128);
            log.responseHeaders(ResponseHeaders.of(HttpStatus.OK,
                                                   HttpHeaderNames.DATE, "some-date"));
            log.responseContent(RPC_RES, THRIFT_REPLY);
            log.endResponse();

            final ILoggingEvent e = log(events);
            final Map<String, String> mdc = e.getMDCPropertyMap();
            assertThat(mdc).containsEntry("local.host", "server.com")
                           .containsEntry("local.ip", "5.6.7.8")
                           .containsEntry("local.port", "8080")
                           .containsEntry("remote.host", "client.com")
                           .containsEntry("remote.ip", "1.2.3.4")
                           .containsEntry("remote.port", "5678")
                           .containsEntry("client.ip", "9.10.11.12")
                           .containsEntry("req.direction", "INBOUND")
                           .containsEntry("req.authority", "server.com:8080")
                           .containsEntry("req.method", "GET")
                           .containsEntry("req.name", RPC_REQ.method())
                           .containsEntry("req.service_name", RPC_REQ.serviceType().getName())
                           .containsEntry("req.path", "/foo")
                           .containsEntry("req.query", "bar=baz")
                           .containsEntry("scheme", "tbinary+h2")
                           .containsEntry("req.content_length", "64")
                           .containsEntry("req.content", "[world]")
                           .containsEntry("res.status_code", "200")
                           .containsEntry("res.content_length", "128")
                           .containsEntry("res.content", "Hello, world!")
                           .containsEntry("req.headers.user-agent", "some-client")
                           .containsEntry("res.headers.date", "some-date")
                           .containsEntry("tls.session_id", "0101020305080d15")
                           .containsEntry("tls.proto", "TLSv1.2")
                           .containsEntry("tls.cipher", "some-cipher")
                           .containsEntry("attrs.my_attr_name", "some-name")
                           .containsEntry("attrs.my_attr_value", "some-value")
                           .containsKey("req.id")
                           .containsKey("req.root_id")
                           .containsKey("elapsed_nanos")
                           .hasSize(30);
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

    private static ServiceRequestContext newServiceContext(
            String path, @Nullable String query) throws Exception {

        final InetSocketAddress remoteAddress = new InetSocketAddress(
                InetAddress.getByAddress("client.com", new byte[] { 1, 2, 3, 4 }), 5678);
        final InetSocketAddress localAddress = new InetSocketAddress(
                InetAddress.getByAddress("server.com", new byte[] { 5, 6, 7, 8 }), 8080);

        final String pathAndQuery = path + (query != null ? '?' + query : "");
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.GET, pathAndQuery,
                                                                 HttpHeaderNames.AUTHORITY, "server.com:8080",
                                                                 HttpHeaderNames.USER_AGENT, "some-client"));

        final ServiceRequestContext ctx =
                ServiceRequestContext.builder(req)
                                     .sslSession(newSslSession())
                                     .remoteAddress(remoteAddress)
                                     .localAddress(localAddress)
                                     .proxiedAddresses(
                                             ProxiedAddresses.of(new InetSocketAddress("9.10.11.12", 0)))
                                     .build();

        ctx.setAttr(CustomObject.ATTR, new CustomObject("some-name", "some-value"));
        ctx.setAttr(CustomObject.FOO, "hello");
        return ctx;
    }

    @Test
    void testClientContextWithMinimalLogs() throws Exception {
        final List<ILoggingEvent> events = prepare(a -> {
            // Export all properties.
            for (BuiltInProperty p : BuiltInProperty.values()) {
                a.addBuiltIn(p);
            }
        });

        final ClientRequestContext ctx = newClientContext("/foo", "type=bar");
        try (SafeCloseable ignored = ctx.push()) {
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
                           .containsEntry("req.query", "type=bar")
                           .containsEntry("scheme", "unknown+h2")
                           .containsEntry("tls.session_id", "0101020305080d15")
                           .containsEntry("tls.proto", "TLSv1.2")
                           .containsEntry("tls.cipher", "some-cipher")
                           .containsKey("req.id")
                           .hasSize(16);
        }
    }

    @Test
    void testClientContextWithFullLogs() throws Exception {
        final List<ILoggingEvent> events = prepare(a -> {
            // Export all properties.
            for (BuiltInProperty p : BuiltInProperty.values()) {
                a.addBuiltIn(p);
            }
            // .. and an attribute.
            a.addAttribute("attrs.my_attr_name", CustomObject.ATTR, new CustomObjectNameStringifier());
            a.addAttribute("attrs.my_attr_value", CustomObject.ATTR, new CustomObjectValueStringifier());
            // .. and some HTTP headers.
            a.addRequestHeader(HttpHeaderNames.USER_AGENT);
            a.addResponseHeader(HttpHeaderNames.DATE);
        });

        final ClientRequestContext ctx = newClientContext("/bar", null);
        try (SafeCloseable ignored = ctx.push()) {
            final RequestLogBuilder log = ctx.logBuilder();
            log.serializationFormat(ThriftSerializationFormats.BINARY);
            log.requestLength(64);
            log.requestHeaders(RequestHeaders.of(HttpMethod.GET, "/bar",
                                                 HttpHeaderNames.USER_AGENT, "some-client"));
            log.requestContent(RPC_REQ, THRIFT_CALL);
            log.endRequest();
            log.responseLength(128);
            log.responseHeaders(ResponseHeaders.of(HttpStatus.OK,
                                                   HttpHeaderNames.DATE, "some-date"));
            log.responseContent(RPC_RES, THRIFT_REPLY);
            log.endResponse();

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
                           .containsEntry("req.path", "/bar")
                           .containsEntry("scheme", "tbinary+h2")
                           .containsEntry("req.name", "hello")
                           .containsEntry("req.content_length", "64")
                           .containsEntry("req.name", "hello")
                           .containsEntry("req.service_name", Object.class.getName())
                           .containsEntry("req.content", "[world]")
                           .containsEntry("res.status_code", "200")
                           .containsEntry("res.content_length", "128")
                           .containsEntry("res.content", "Hello, world!")
                           .containsEntry("req.headers.user-agent", "some-client")
                           .containsEntry("res.headers.date", "some-date")
                           .containsEntry("tls.session_id", "0101020305080d15")
                           .containsEntry("tls.proto", "TLSv1.2")
                           .containsEntry("tls.cipher", "some-cipher")
                           .containsEntry("attrs.my_attr_name", "some-name")
                           .containsEntry("attrs.my_attr_value", "some-value")
                           .containsKey("req.id")
                           .containsKey("elapsed_nanos")
                           .hasSize(27);
        }
    }

    private static ClientRequestContext newClientContext(
            String path, @Nullable String query) throws Exception {

        final InetSocketAddress remoteAddress = new InetSocketAddress(
                InetAddress.getByAddress("server.com", new byte[] { 1, 2, 3, 4 }), 8080);
        final InetSocketAddress localAddress = new InetSocketAddress(
                InetAddress.getByAddress("client.com", new byte[] { 5, 6, 7, 8 }), 5678);

        final String pathAndQuery = path + (query != null ? '?' + query : "");
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.GET, pathAndQuery,
                                                                 HttpHeaderNames.AUTHORITY, "server.com:8080",
                                                                 HttpHeaderNames.USER_AGENT, "some-client"));

        final ClientRequestContext ctx =
                ClientRequestContext.builder(req)
                                    .remoteAddress(remoteAddress)
                                    .localAddress(localAddress)
                                    .endpoint(Endpoint.of("server.com", 8080))
                                    .sslSession(newSslSession())
                                    .build();

        ctx.setAttr(CustomObject.ATTR, new CustomObject("some-name", "some-value"));
        return ctx;
    }

    private static SSLSession newSslSession() {
        final SSLSession sslSession = mock(SSLSession.class, withSettings().lenient());
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
