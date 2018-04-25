/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.server.logging;

import static com.linecorp.armeria.server.logging.AccessLogComponent.CommonComponent.dateTimeFormatter;
import static com.linecorp.armeria.server.logging.AccessLogComponent.CommonComponent.defaultZoneId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.function.Function;

import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.linecorp.armeria.common.DefaultRpcRequest;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.NonWrappingRequestContext;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.DefaultRequestLog;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.metric.NoopMeterRegistry;
import com.linecorp.armeria.server.logging.AccessLogComponent.AttributeComponent;
import com.linecorp.armeria.server.logging.AccessLogComponent.CommonComponent;
import com.linecorp.armeria.server.logging.AccessLogComponent.RequestHeaderComponent;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.util.AttributeKey;

public class AccessLogFormatsTest {

    @Rule
    public MockitoRule mocks = MockitoJUnit.rule();

    @Mock
    private Channel channel;

    @Test
    public void parseSuccess() {
        List<AccessLogComponent> format;
        AccessLogComponent entry;
        RequestHeaderComponent headerEntry;
        CommonComponent commonComponentEntry;

        assertThat(AccessLogFormats.parseCustom("%h %l"))
                .usingRecursiveFieldByFieldElementComparator()
                .containsSequence(AccessLogComponent.ofPredefinedCommon(AccessLogType.REMOTE_HOST),
                                  AccessLogComponent.ofText(" "),
                                  AccessLogComponent.ofPredefinedCommon(AccessLogType.RFC931));

        format = AccessLogFormats.parseCustom("%200,302{Referer}i");
        assertThat(format.size()).isOne();
        entry = format.get(0);
        assertThat(entry).isInstanceOf(RequestHeaderComponent.class);
        headerEntry = (RequestHeaderComponent) entry;
        assertThat(headerEntry.condition()).isNotNull();
        assertThat(headerEntry.condition().apply(HttpHeaders.of(HttpStatus.OK))).isTrue();
        assertThat(headerEntry.condition().apply(HttpHeaders.of(HttpStatus.BAD_REQUEST))).isFalse();
        assertThat(headerEntry.headerName().toString())
                .isEqualToIgnoringCase(HttpHeaderNames.REFERER.toString());

        format = AccessLogFormats.parseCustom("%!200,302{User-Agent}i");
        assertThat(format.size()).isOne();
        entry = format.get(0);
        assertThat(entry).isInstanceOf(RequestHeaderComponent.class);
        headerEntry = (RequestHeaderComponent) entry;
        assertThat(headerEntry.condition()).isNotNull();
        assertThat(headerEntry.condition().apply(HttpHeaders.of(HttpStatus.OK))).isFalse();
        assertThat(headerEntry.condition().apply(HttpHeaders.of(HttpStatus.BAD_REQUEST))).isTrue();
        assertThat(headerEntry.headerName().toString())
                .isEqualToIgnoringCase(HttpHeaderNames.USER_AGENT.toString());

        format = AccessLogFormats.parseCustom("%200b");
        assertThat(format.size()).isOne();
        entry = format.get(0);
        assertThat(entry).isInstanceOf(CommonComponent.class);
        commonComponentEntry = (CommonComponent) entry;
        assertThat(commonComponentEntry.condition()).isNotNull();
        assertThat(commonComponentEntry.condition().apply(HttpHeaders.of(HttpStatus.OK))).isTrue();
        assertThat(commonComponentEntry.condition().apply(HttpHeaders.of(HttpStatus.BAD_REQUEST))).isFalse();

        format = AccessLogFormats.parseCustom("%!200b");
        assertThat(format.size()).isOne();
        entry = format.get(0);
        assertThat(entry).isInstanceOf(CommonComponent.class);
        commonComponentEntry = (CommonComponent) entry;
        assertThat(commonComponentEntry.condition()).isNotNull();
        assertThat(commonComponentEntry.condition().apply(HttpHeaders.of(HttpStatus.OK))).isFalse();
        assertThat(commonComponentEntry.condition().apply(HttpHeaders.of(HttpStatus.BAD_REQUEST))).isTrue();

        assertThat(AccessLogFormats.parseCustom("").isEmpty()).isTrue();

        format = AccessLogFormats.parseCustom(
                "%{com.linecorp.armeria.server.logging.AccessLogFormatsTest$Attr#KEY" +
                ":com.linecorp.armeria.server.logging.AccessLogFormatsTest$AttributeStringfier}j");
        assertThat(format.size()).isOne();
        entry = format.get(0);
        assertThat(entry).isInstanceOf(AttributeComponent.class);
        AttributeComponent attrEntry = (AttributeComponent) entry;
        assertThat(attrEntry.key().toString())
                .isEqualTo("com.linecorp.armeria.server.logging.AccessLogFormatsTest$Attr#KEY");

        // Typo, but successful.
        assertThat(AccessLogFormats.parseCustom("%h00,300{abc}"))
                .usingRecursiveFieldByFieldElementComparator()
                .containsSequence(AccessLogComponent.ofPredefinedCommon(AccessLogType.REMOTE_HOST),
                                  AccessLogComponent.ofText("00,300{abc}"));
    }

    @Test
    public void parseFailure() {
        assertThatThrownBy(() -> AccessLogFormats.parseCustom("%x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccessLogFormats.parseCustom("%!{abc}i"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccessLogFormats.parseCustom("%{abci"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccessLogFormats.parseCustom("%{abc}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccessLogFormats.parseCustom("%200,300{abc}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccessLogFormats.parseCustom("%200,30x{abc}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccessLogFormats.parseCustom("%200,300{abc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccessLogFormats.parseCustom("%x00,300{abc}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void formatMessage() {
        final DummyRequestContext ctx = new DummyRequestContext();
        final DefaultRequestLog log = spy(new DefaultRequestLog(ctx));

        ctx.attr(Attr.ATTR_KEY).set(new Attr("line"));

        log.startRequest(channel, SessionProtocol.H2, "www.example.com");
        log.requestHeaders(HttpHeaders.of(HttpMethod.GET, "/armeria/log")
                                      .add(HttpHeaderNames.USER_AGENT, "armeria/x.y.z")
                                      .add(HttpHeaderNames.REFERER, "http://log.example.com")
                                      .add(HttpHeaderNames.COOKIE, "a=1;b=2"));
        log.endRequest();
        log.responseHeaders(HttpHeaders.of(HttpStatus.OK));
        log.responseLength(1024);
        log.endResponse();

        // To generate the same datetime string always.
        when(log.requestStartTimeMillis()).thenReturn(0L);

        final String timestamp = dateTimeFormatter.format(ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(0), defaultZoneId));

        String message;
        List<AccessLogComponent> format;

        message = AccessLogger.format(AccessLogFormats.COMMON, log);
        assertThat(message).isEqualTo(
                "- - - " + timestamp + " \"GET /armeria/log h2\" 200 1024");

        message = AccessLogger.format(AccessLogFormats.COMBINED, log);
        assertThat(message).isEqualTo(
                "- - - " + timestamp + " \"GET /armeria/log h2\" 200 1024" +
                " \"http://log.example.com\" \"armeria/x.y.z\" \"a=1;b=2\"");

        // Check conditions with custom formats.
        format = AccessLogFormats.parseCustom(
                "%h %l %u %t \"%r\" %s %b \"%200,302{Referer}i\" \"%!200,304{User-Agent}i\"" +
                " some-text %{Non-Existing-Header}i");

        message = AccessLogger.format(format, log);
        assertThat(message).isEqualTo(
                "- - - " + timestamp + " \"GET /armeria/log h2\" 200 1024" +
                " \"http://log.example.com\" \"-\" some-text -");

        format = AccessLogFormats.parseCustom(
                "%h %l %u %t \"%r\" %s %b \"%!200,302{Referer}i\" \"%200,304{User-Agent}i\"" +
                " some-text %{Non-Existing-Header}i");
        message = AccessLogger.format(format, log);
        assertThat(message).isEqualTo(
                "- - - " + timestamp + " \"GET /armeria/log h2\" 200 1024" +
                " \"-\" \"armeria/x.y.z\" some-text -");

        format = AccessLogFormats.parseCustom(
                "%{com.linecorp.armeria.server.logging.AccessLogFormatsTest$Attr#KEY" +
                ":com.linecorp.armeria.server.logging.AccessLogFormatsTest$AttributeStringfier}j");
        message = AccessLogger.format(format, log);
        assertThat(message).isEqualTo("(line)");

        format = AccessLogFormats.parseCustom(
                "%{com.linecorp.armeria.server.logging.AccessLogFormatsTest$Attr#KEY}j");
        message = AccessLogger.format(format, log);
        assertThat(message).isEqualTo("LINE");
    }

    @Test
    public void requestLogAvailabilityException() {
        final String expectedLogMessage = "\"GET /armeria/log#rpcMethod h2\" 200 1024";

        final DummyRequestContext ctx = new DummyRequestContext();
        final DefaultRequestLog log = spy(new DefaultRequestLog(ctx));

        // AccessLogger#format will be called after response is finished.
        log.addListener(l -> assertThat(AccessLogger.format(AccessLogFormats.COMMON, l))
                .endsWith(expectedLogMessage), RequestLogAvailability.COMPLETE);

        ctx.attr(Attr.ATTR_KEY).set(new Attr("line"));

        log.startRequest(channel, SessionProtocol.H2, "www.example.com");
        log.requestHeaders(HttpHeaders.of(HttpMethod.GET, "/armeria/log")
                                      .add(HttpHeaderNames.USER_AGENT, "armeria/x.y.z")
                                      .add(HttpHeaderNames.REFERER, "http://log.example.com")
                                      .add(HttpHeaderNames.COOKIE, "a=1;b=2"));

        // RequestLogAvailabilityException will be raised inside AccessLogger#format before injecting each
        // component to RequestLog. So we cannot get the expected log message here.
        assertThat(AccessLogger.format(AccessLogFormats.COMMON, log)).doesNotEndWith(expectedLogMessage);
        log.requestContent(new DefaultRpcRequest(Object.class, "rpcMethod"), null);
        assertThat(AccessLogger.format(AccessLogFormats.COMMON, log)).doesNotEndWith(expectedLogMessage);
        log.endRequest();
        assertThat(AccessLogger.format(AccessLogFormats.COMMON, log)).doesNotEndWith(expectedLogMessage);
        log.responseHeaders(HttpHeaders.of(HttpStatus.OK));
        assertThat(AccessLogger.format(AccessLogFormats.COMMON, log)).doesNotEndWith(expectedLogMessage);
        log.responseLength(1024);
        assertThat(AccessLogger.format(AccessLogFormats.COMMON, log)).doesNotEndWith(expectedLogMessage);
        log.endResponse();
    }

    private class DummyRequestContext extends NonWrappingRequestContext {
        DummyRequestContext() {
            super(NoopMeterRegistry.get(), SessionProtocol.HTTP,
                  HttpMethod.GET, "/", null, HttpRequest.of(HttpMethod.GET, "/"));
        }

        @Override
        public RequestContext newDerivedContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public RequestContext newDerivedContext(Request request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EventLoop eventLoop() {
            return channel.eventLoop();
        }

        @Override
        protected Channel channel() {
            return channel;
        }

        @Override
        public RequestLog log() {
            throw new UnsupportedOperationException();
        }

        @Override
        public RequestLogBuilder logBuilder() {
            throw new UnsupportedOperationException();
        }
    }

    public static class Attr {
        static final AttributeKey<Attr> ATTR_KEY = AttributeKey.valueOf(Attr.class, "KEY");

        private final String member;

        Attr(String member) {
            this.member = member;
        }

        public String member() {
            return member;
        }

        @Override
        public String toString() {
            return member().toUpperCase();
        }
    }

    public static class AttributeStringfier implements Function<Attr, String> {
        @Override
        public String apply(Attr attr) {
            return '(' + attr.member() + ')';
        }
    }
}
