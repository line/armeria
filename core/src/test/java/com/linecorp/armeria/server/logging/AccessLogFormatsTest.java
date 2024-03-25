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

import static com.linecorp.armeria.server.logging.AccessLogComponent.TimestampComponent.defaultDateTimeFormatter;
import static com.linecorp.armeria.server.logging.AccessLogComponent.TimestampComponent.defaultZoneId;
import static java.time.format.DateTimeFormatter.BASIC_ISO_DATE;
import static java.time.format.DateTimeFormatter.ISO_DATE;
import static java.time.format.DateTimeFormatter.ISO_DATE_TIME;
import static java.time.format.DateTimeFormatter.ISO_INSTANT;
import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME;
import static java.time.format.DateTimeFormatter.ISO_LOCAL_TIME;
import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE;
import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;
import static java.time.format.DateTimeFormatter.ISO_OFFSET_TIME;
import static java.time.format.DateTimeFormatter.ISO_ORDINAL_DATE;
import static java.time.format.DateTimeFormatter.ISO_TIME;
import static java.time.format.DateTimeFormatter.ISO_WEEK_DATE;
import static java.time.format.DateTimeFormatter.ISO_ZONED_DATE_TIME;
import static java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.internal.testing.ImmediateEventLoop;
import com.linecorp.armeria.server.ProxiedAddresses;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.logging.AccessLogComponent.AttributeComponent;
import com.linecorp.armeria.server.logging.AccessLogComponent.CommonComponent;
import com.linecorp.armeria.server.logging.AccessLogComponent.HttpHeaderComponent;

import io.netty.util.AttributeKey;
import io.netty.util.NetUtil;

class AccessLogFormatsTest {

    private static final Duration duration = Duration.ofMillis(1000000000L);

    // The timestamp of first commit in Armeria project.
    private static final long requestStartTimeMillis = 1447656026L * 1000;
    private static final long requestStartTimeMicros = requestStartTimeMillis * 1000;
    private static final long requestStartTimeNanos = 42424242424242L; // Some random number.
    private static final long requestEndTimeNanos = requestStartTimeNanos + duration.toNanos();

    @Test
    void parseSuccess() {
        List<AccessLogComponent> format;
        AccessLogComponent entry;
        HttpHeaderComponent headerEntry;
        CommonComponent commonComponentEntry;

        assertThat(AccessLogFormats.parseCustom("%h %l"))
                .usingRecursiveFieldByFieldElementComparator()
                .containsSequence(AccessLogComponent.ofPredefinedCommon(AccessLogType.REMOTE_HOST),
                                  AccessLogComponent.ofText(" "),
                                  AccessLogComponent.ofPredefinedCommon(AccessLogType.RFC931));

        format = AccessLogFormats.parseCustom("%200,302{Referer}i");
        assertThat(format.size()).isOne();
        entry = format.get(0);
        assertThat(entry).isInstanceOf(HttpHeaderComponent.class);
        headerEntry = (HttpHeaderComponent) entry;
        assertThat(headerEntry.condition()).isNotNull();
        assertThat(headerEntry.condition().apply(ResponseHeaders.of(HttpStatus.OK))).isTrue();
        assertThat(headerEntry.condition().apply(ResponseHeaders.of(HttpStatus.BAD_REQUEST))).isFalse();
        assertThat(headerEntry.headerName().toString())
                .isEqualToIgnoringCase(HttpHeaderNames.REFERER.toString());

        format = AccessLogFormats.parseCustom("%!200,302{User-Agent}i");
        assertThat(format.size()).isOne();
        entry = format.get(0);
        assertThat(entry).isInstanceOf(HttpHeaderComponent.class);
        headerEntry = (HttpHeaderComponent) entry;
        assertThat(headerEntry.condition()).isNotNull();
        assertThat(headerEntry.condition().apply(ResponseHeaders.of(HttpStatus.OK))).isFalse();
        assertThat(headerEntry.condition().apply(ResponseHeaders.of(HttpStatus.BAD_REQUEST))).isTrue();
        assertThat(headerEntry.headerName().toString())
                .isEqualToIgnoringCase(HttpHeaderNames.USER_AGENT.toString());

        format = AccessLogFormats.parseCustom("%200b");
        assertThat(format.size()).isOne();
        entry = format.get(0);
        assertThat(entry).isInstanceOf(CommonComponent.class);
        commonComponentEntry = (CommonComponent) entry;
        assertThat(commonComponentEntry.condition()).isNotNull();
        assertThat(commonComponentEntry.condition().apply(ResponseHeaders.of(HttpStatus.OK))).isTrue();
        assertThat(commonComponentEntry.condition()
                                       .apply(ResponseHeaders.of(HttpStatus.BAD_REQUEST))).isFalse();

        format = AccessLogFormats.parseCustom("%!200b");
        assertThat(format.size()).isOne();
        entry = format.get(0);
        assertThat(entry).isInstanceOf(CommonComponent.class);
        commonComponentEntry = (CommonComponent) entry;
        assertThat(commonComponentEntry.condition()).isNotNull();
        assertThat(commonComponentEntry.condition().apply(ResponseHeaders.of(HttpStatus.OK))).isFalse();
        assertThat(commonComponentEntry.condition().apply(ResponseHeaders.of(HttpStatus.BAD_REQUEST))).isTrue();

        assertThat(AccessLogFormats.parseCustom("").isEmpty()).isTrue();

        format = AccessLogFormats.parseCustom(
                "%{com.linecorp.armeria.server.logging.AccessLogFormatsTest$Attr#KEY" +
                ":com.linecorp.armeria.server.logging.AccessLogFormatsTest$AttributeStringifier}j");
        assertThat(format.size()).isOne();
        entry = format.get(0);
        assertThat(entry).isInstanceOf(AttributeComponent.class);
        final AttributeComponent attrEntry = (AttributeComponent) entry;
        assertThat(attrEntry.key().toString())
                .isEqualTo("com.linecorp.armeria.server.logging.AccessLogFormatsTest$Attr#KEY");

        // Typo, but successful.
        assertThat(AccessLogFormats.parseCustom("%h00,300{abc}"))
                .usingRecursiveFieldByFieldElementComparator()
                .containsSequence(AccessLogComponent.ofPredefinedCommon(AccessLogType.REMOTE_HOST),
                                  AccessLogComponent.ofText("00,300{abc}"));

        assertThat(AccessLogFormats.parseCustom("%a %{c}a %A"))
                .usingRecursiveFieldByFieldElementComparator()
                .containsSequence(AccessLogComponent.ofPredefinedCommon(AccessLogType.REMOTE_IP_ADDRESS),
                                  AccessLogComponent.ofText(" "),
                                  AccessLogComponent.ofPredefinedCommon(AccessLogType.REMOTE_IP_ADDRESS, "c"),
                                  AccessLogComponent.ofText(" "),
                                  AccessLogComponent.ofPredefinedCommon(AccessLogType.LOCAL_IP_ADDRESS));
    }

    @Test
    void parseFailure() {
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
    void formatMessage() {
        final HttpRequest req = HttpRequest.of(
                RequestHeaders.of(HttpMethod.GET, "/armeria/log",
                                  HttpHeaderNames.USER_AGENT, "armeria/x.y.z",
                                  HttpHeaderNames.REFERER, "http://log.example.com",
                                  HttpHeaderNames.COOKIE, "a=1;b=2"));
        final RequestId id = RequestId.random();
        final ServiceRequestContext ctx =
                ServiceRequestContext.builder(req)
                                     .requestStartTime(requestStartTimeNanos, requestStartTimeMicros)
                                     .id(id)
                                     .build();
        ctx.setAttr(Attr.ATTR_KEY, new Attr("line"));
        final RequestLogBuilder logBuilder = ctx.logBuilder();
        logBuilder.authenticatedUser("foo");
        logBuilder.endRequest();
        ctx.log().ensureRequestComplete();

        logBuilder.responseHeaders(ResponseHeaders.of(HttpStatus.OK,
                                                      HttpHeaderNames.CONTENT_TYPE,
                                                      MediaType.PLAIN_TEXT_UTF_8));
        logBuilder.responseLength(1024);
        logBuilder.endResponse();

        final RequestLog log = ctx.log().ensureComplete();
        final String serviceName = log.serviceName();
        final String logName = serviceName.substring(serviceName.lastIndexOf('.') + 1);

        final String localhostAddress = NetUtil.LOCALHOST.getHostAddress();
        final String timestamp = defaultDateTimeFormatter.format(ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(requestStartTimeMillis), defaultZoneId));

        String message;
        List<AccessLogComponent> format;

        message = AccessLogger.format(AccessLogFormats.COMMON, log);
        assertThat(message).isEqualTo(
                localhostAddress + " - foo " + timestamp + " \"GET /armeria/log#" + logName +
                " h2c\" 200 1024");

        message = AccessLogger.format(AccessLogFormats.COMBINED, log);
        assertThat(message).isEqualTo(
                localhostAddress + " - foo " + timestamp + " \"GET /armeria/log#" + logName +
                " h2c\" 200 1024 \"http://log.example.com\" \"armeria/x.y.z\" \"a=1;b=2\"");

        // Check conditions with custom formats.
        format = AccessLogFormats.parseCustom(
                "%h %l %u %t \"%r\" %s %b \"%200,302{Referer}i\" \"%!200,304{User-Agent}i\"" +
                " some-text %{Non-Existing-Header}i");

        message = AccessLogger.format(format, log);
        assertThat(message).isEqualTo(
                localhostAddress + " - foo " + timestamp + " \"GET /armeria/log#" + logName +
                " h2c\" 200 1024 \"http://log.example.com\" \"-\" some-text -");

        format = AccessLogFormats.parseCustom(
                "%h %l %u %t \"%r\" %s %b \"%!200,302{Referer}i\" \"%200,304{User-Agent}i\"" +
                " some-text %{Non-Existing-Header}i");
        message = AccessLogger.format(format, log);
        assertThat(message).isEqualTo(
                localhostAddress + " - foo " + timestamp + " \"GET /armeria/log#" + logName +
                " h2c\" 200 1024 \"-\" \"armeria/x.y.z\" some-text -");

        format = AccessLogFormats.parseCustom(
                "%{com.linecorp.armeria.server.logging.AccessLogFormatsTest$Attr#KEY" +
                ":com.linecorp.armeria.server.logging.AccessLogFormatsTest$AttributeStringifier}j");
        message = AccessLogger.format(format, log);
        assertThat(message).isEqualTo("(line)");

        format = AccessLogFormats.parseCustom(
                "%{com.linecorp.armeria.server.logging.AccessLogFormatsTest$Attr#KEY}j");
        message = AccessLogger.format(format, log);
        assertThat(message).isEqualTo("LINE");

        format = AccessLogFormats.parseCustom("%{content-type}o");
        assertThat(AccessLogger.format(format, log)).isEqualTo(MediaType.PLAIN_TEXT_UTF_8.toString());

        format = AccessLogFormats.parseCustom("%I");
        assertThat(AccessLogger.format(format, log)).isEqualTo(id.text());

        format = AccessLogFormats.parseCustom("%{short}I");
        assertThat(AccessLogger.format(format, log)).isEqualTo(id.shortText());
    }

    @CsvSource({
            "armeria.LogService, write, LogService/write",
            "LogService, write, LogService/write",
            "LogService, POST, LogService/POST",
            "LogService, GET, LogService",
    })
    @ParameterizedTest
    void formatWithLogName(String serviceName, String name, String logName) {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/armeria/log");
        final ServiceRequestContext ctx =
                ServiceRequestContext.builder(req)
                                     .requestStartTime(requestStartTimeNanos, requestStartTimeMicros)
                                     .build();

        final RequestLogBuilder logBuilder = ctx.logBuilder();
        logBuilder.name(serviceName, name);
        logBuilder.endRequest();
        ctx.log().ensureRequestComplete();

        logBuilder.responseHeaders(ResponseHeaders.of(HttpStatus.OK,
                                                      HttpHeaderNames.CONTENT_TYPE,
                                                      MediaType.PLAIN_TEXT_UTF_8));
        logBuilder.responseLength(1024);
        logBuilder.endResponse();

        final RequestLog log = ctx.log().ensureComplete();

        final String localhostAddress = NetUtil.LOCALHOST.getHostAddress();
        final String timestamp = defaultDateTimeFormatter.format(ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(requestStartTimeMillis), defaultZoneId));

        final String message = AccessLogger.format(AccessLogFormats.COMMON, log);
        assertThat(message).isEqualTo(
                localhostAddress + " - - " + timestamp + " \"GET /armeria/log#" + logName + " h2c\" 200 1024");
    }

    @Test
    void logClientAddress() throws Exception {
        final InetSocketAddress remote = new InetSocketAddress(InetAddress.getByName("10.1.0.1"), 0);
        final ProxiedAddresses proxied = ProxiedAddresses.of(
                new InetSocketAddress("10.1.0.2", 5000));
        final ServiceRequestContext ctx =
                ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                     .remoteAddress(remote)
                                     .proxiedAddresses(proxied)
                                     .build();
        ctx.logBuilder().endRequest();
        ctx.logBuilder().endResponse();

        final RequestLog log = ctx.log().ensureComplete();

        List<AccessLogComponent> format;

        // Client IP address
        format = AccessLogFormats.parseCustom("%a");
        assertThat(AccessLogger.format(format, log)).isEqualTo("10.1.0.2");

        // Remote IP address of a channel
        format = AccessLogFormats.parseCustom("%{c}a");
        assertThat(AccessLogger.format(format, log)).isEqualTo("10.1.0.1");
    }

    @Test
    void requestLogAvailabilityException() {
        final String fullName = AccessLogFormatsTest.class.getSimpleName() + "/rpcMethod";
        final String expectedLogMessage = "\"GET /armeria/log#" + fullName + " h2c\" 200 1024";

        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/armeria/log",
                                                                 HttpHeaderNames.USER_AGENT, "armeria/x.y.z",
                                                                 HttpHeaderNames.REFERER,
                                                                 "http://log.example.com",
                                                                 HttpHeaderNames.COOKIE, "a=1;b=2"));
        final ServiceRequestContext ctx =
                ServiceRequestContext.builder(req)
                                     .eventLoop(ImmediateEventLoop.INSTANCE)
                                     .build();
        final RequestLog log = ctx.log().partial();
        final RequestLogBuilder logBuilder = ctx.logBuilder();

        // AccessLogger#format will be called after response is finished.
        final AtomicReference<RequestLog> logHolder = new AtomicReference<>();
        log.whenComplete().thenAccept(logHolder::set);

        // RequestLogAvailabilityException will be raised inside AccessLogger#format before injecting each
        // component to RequestLog. So we cannot get the expected log message here.
        assertThat(AccessLogger.format(AccessLogFormats.COMMON, log)).doesNotEndWith(expectedLogMessage);
        logBuilder.requestContent(RpcRequest.of(AccessLogFormatsTest.class, "rpcMethod"), null);
        assertThat(AccessLogger.format(AccessLogFormats.COMMON, log)).doesNotEndWith(expectedLogMessage);
        logBuilder.endRequest();
        assertThat(AccessLogger.format(AccessLogFormats.COMMON, log)).doesNotEndWith(expectedLogMessage);
        logBuilder.responseHeaders(ResponseHeaders.of(HttpStatus.OK));
        assertThat(AccessLogger.format(AccessLogFormats.COMMON, log)).doesNotEndWith(expectedLogMessage);
        logBuilder.responseLength(1024);
        assertThat(AccessLogger.format(AccessLogFormats.COMMON, log)).doesNotEndWith(expectedLogMessage);
        logBuilder.endResponse();

        assertThat(AccessLogger.format(AccessLogFormats.COMMON, logHolder.get()))
                .endsWith(expectedLogMessage);
    }

    @Test
    void requestLogComponent() {
        final ServiceRequestContext ctx =
                ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/armeria/log"))
                                     .requestStartTime(requestStartTimeNanos, requestStartTimeMicros)
                                     .build();

        final RequestLog log = ctx.log().partial();
        final RequestLogBuilder logBuilder = ctx.logBuilder();

        List<AccessLogComponent> format;

        final Instant requestStartTime = Instant.ofEpochMilli(requestStartTimeMillis);

        logBuilder.endRequest(new IllegalArgumentException("detail_message"), requestEndTimeNanos);

        format = AccessLogFormats.parseCustom("%{requestStartTimeMillis}L " +
                                              "%{requestEndTimeMillis}L " +
                                              "%{requestDurationMillis}L");
        assertThat(AccessLogger.format(format, log)).isEqualTo(
                String.join(" ",
                            String.valueOf(requestStartTime.toEpochMilli()),
                            String.valueOf(requestStartTime.plus(duration).toEpochMilli()),
                            String.valueOf(duration.toMillis())));

        format = AccessLogFormats.parseCustom("\"%{requestCause}L\"");
        //assertThat(AccessLogger.format(format, log)).isEqualTo("\"-\"");
        assertThat(AccessLogger.format(format, log))
                .isEqualTo('"' + IllegalArgumentException.class.getSimpleName() + ": detail_message\"");

        format = AccessLogFormats.parseCustom("%{responseStartTimeMillis}L " +
                                              "%{responseEndTimeMillis}L " +
                                              "%{responseDurationMillis}L");
        // No values.
        assertThat(AccessLogger.format(format, log)).isEqualTo("- - -");

        final Duration latency = Duration.ofSeconds(3);
        final Instant responseStartTime = requestStartTime.plus(latency);
        final long responseStartTimeNanos = requestStartTimeNanos + latency.toNanos();

        logBuilder.startResponse(responseStartTimeNanos, responseStartTime.toEpochMilli() * 1000);
        logBuilder.endResponse(new IllegalArgumentException(),
                               responseStartTimeNanos + duration.toNanos());

        assertThat(AccessLogger.format(format, log)).isEqualTo(
                String.join(" ",
                            String.valueOf(responseStartTime.toEpochMilli()),
                            String.valueOf(responseStartTime.plus(duration).toEpochMilli()),
                            String.valueOf(duration.toMillis())));

        format = AccessLogFormats.parseCustom("\"%{responseCause}L\"");
        //assertThat(AccessLogger.format(format, log)).isEqualTo("\"-\"");
        assertThat(AccessLogger.format(format, log))
                .isEqualTo('"' + IllegalArgumentException.class.getSimpleName() + '"');
    }

    @Test
    void requestLogWithEmptyCause() {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

        final RequestLogBuilder logBuilder = ctx.logBuilder();

        final List<AccessLogComponent> format =
                AccessLogFormats.parseCustom("%{requestCause}L %{responseCause}L");

        logBuilder.endRequest();
        logBuilder.endResponse();

        assertThat(AccessLogger.format(format, ctx.log().ensureComplete())).isEqualTo("- -");
    }

    @Test
    void timestamp() {
        final ServiceRequestContext ctx =
                ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                     .requestStartTime(requestStartTimeNanos, requestStartTimeMicros)
                                     .build();
        final RequestLog log = ctx.log().partial();

        assertThat(AccessLogger.format(AccessLogFormats.parseCustom("%t"), log))
                .isEqualTo(formatString(defaultDateTimeFormatter, requestStartTimeMillis));
        assertThat(AccessLogger.format(AccessLogFormats.parseCustom("%{BASIC_ISO_DATE}t"), log))
                .isEqualTo(formatString(BASIC_ISO_DATE, requestStartTimeMillis));
        assertThat(AccessLogger.format(AccessLogFormats.parseCustom("%{ISO_LOCAL_DATE}t"), log))
                .isEqualTo(formatString(ISO_LOCAL_DATE, requestStartTimeMillis));
        assertThat(AccessLogger.format(AccessLogFormats.parseCustom("%{ISO_OFFSET_DATE}t"), log))
                .isEqualTo(formatString(ISO_OFFSET_DATE, requestStartTimeMillis));
        assertThat(AccessLogger.format(AccessLogFormats.parseCustom("%{ISO_DATE}t"), log))
                .isEqualTo(formatString(ISO_DATE, requestStartTimeMillis));
        assertThat(AccessLogger.format(AccessLogFormats.parseCustom("%{ISO_LOCAL_TIME}t"), log))
                .isEqualTo(formatString(ISO_LOCAL_TIME, requestStartTimeMillis));
        assertThat(AccessLogger.format(AccessLogFormats.parseCustom("%{ISO_OFFSET_TIME}t"), log))
                .isEqualTo(formatString(ISO_OFFSET_TIME, requestStartTimeMillis));
        assertThat(AccessLogger.format(AccessLogFormats.parseCustom("%{ISO_TIME}t"), log))
                .isEqualTo(formatString(ISO_TIME, requestStartTimeMillis));
        assertThat(AccessLogger.format(AccessLogFormats.parseCustom("%{ISO_LOCAL_DATE_TIME}t"), log))
                .isEqualTo(formatString(ISO_LOCAL_DATE_TIME, requestStartTimeMillis));
        assertThat(AccessLogger.format(AccessLogFormats.parseCustom("%{ISO_OFFSET_DATE_TIME}t"), log))
                .isEqualTo(formatString(ISO_OFFSET_DATE_TIME, requestStartTimeMillis));
        assertThat(AccessLogger.format(AccessLogFormats.parseCustom("%{ISO_ZONED_DATE_TIME}t"), log))
                .isEqualTo(formatString(ISO_ZONED_DATE_TIME, requestStartTimeMillis));
        assertThat(AccessLogger.format(AccessLogFormats.parseCustom("%{ISO_DATE_TIME}t"), log))
                .isEqualTo(formatString(ISO_DATE_TIME, requestStartTimeMillis));
        assertThat(AccessLogger.format(AccessLogFormats.parseCustom("%{ISO_ORDINAL_DATE}t"), log))
                .isEqualTo(formatString(ISO_ORDINAL_DATE, requestStartTimeMillis));
        assertThat(AccessLogger.format(AccessLogFormats.parseCustom("%{ISO_WEEK_DATE}t"), log))
                .isEqualTo(formatString(ISO_WEEK_DATE, requestStartTimeMillis));
        assertThat(AccessLogger.format(AccessLogFormats.parseCustom("%{ISO_INSTANT}t"), log))
                .isEqualTo(formatString(ISO_INSTANT, requestStartTimeMillis));
        assertThat(AccessLogger.format(AccessLogFormats.parseCustom("%{RFC_1123_DATE_TIME}t"), log))
                .isEqualTo(formatString(RFC_1123_DATE_TIME, requestStartTimeMillis));
        assertThat(AccessLogger.format(AccessLogFormats.parseCustom("%{yyyy MM dd}t"), log))
                .isEqualTo(formatString(DateTimeFormatter.ofPattern("yyyy MM dd"), requestStartTimeMillis));
    }

    private static String formatString(DateTimeFormatter formatter, long millis) {
        return formatter.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), defaultZoneId));
    }

    static class Attr {
        static final AttributeKey<Attr> ATTR_KEY = AttributeKey.valueOf(Attr.class, "KEY");

        private final String member;

        Attr(String member) {
            this.member = member;
        }

        String member() {
            return member;
        }

        @Override
        public String toString() {
            return member().toUpperCase();
        }
    }

    @SuppressWarnings("unused")
    static class AttributeStringifier implements Function<Attr, String> {
        @Override
        public String apply(Attr attr) {
            return '(' + attr.member() + ')';
        }
    }
}
