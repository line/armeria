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
import java.util.function.Function;

import org.junit.Test;

import com.linecorp.armeria.common.DefaultRpcRequest;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.ServiceRequestContextBuilder;
import com.linecorp.armeria.server.logging.AccessLogComponent.AttributeComponent;
import com.linecorp.armeria.server.logging.AccessLogComponent.CommonComponent;
import com.linecorp.armeria.server.logging.AccessLogComponent.HttpHeaderComponent;

import io.netty.util.AttributeKey;
import io.netty.util.NetUtil;

public class AccessLogFormatsTest {

    private static final Duration duration = Duration.ofMillis(1000000000L);

    // The timestamp of first commit in Armeria project.
    private static final long requestStartTimeMillis = 1447656026L * 1000;
    private static final long requestStartTimeMicros = requestStartTimeMillis * 1000;
    private static final long requestStartTimeNanos = 42424242424242L; // Some random number.
    private static final long requestEndTimeNanos = requestStartTimeNanos + duration.toNanos();

    @Test
    public void parseSuccess() {
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
        assertThat(headerEntry.condition().apply(HttpHeaders.of(HttpStatus.OK))).isTrue();
        assertThat(headerEntry.condition().apply(HttpHeaders.of(HttpStatus.BAD_REQUEST))).isFalse();
        assertThat(headerEntry.headerName().toString())
                .isEqualToIgnoringCase(HttpHeaderNames.REFERER.toString());

        format = AccessLogFormats.parseCustom("%!200,302{User-Agent}i");
        assertThat(format.size()).isOne();
        entry = format.get(0);
        assertThat(entry).isInstanceOf(HttpHeaderComponent.class);
        headerEntry = (HttpHeaderComponent) entry;
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
        final HttpRequest req = HttpRequest.of(
                HttpHeaders.of(HttpMethod.GET, "/armeria/log")
                           .add(HttpHeaderNames.USER_AGENT, "armeria/x.y.z")
                           .add(HttpHeaderNames.REFERER, "http://log.example.com")
                           .add(HttpHeaderNames.COOKIE, "a=1;b=2"));
        final ServiceRequestContext ctx =
                ServiceRequestContextBuilder.of(req)
                                            .requestStartTime(requestStartTimeNanos, requestStartTimeMicros)
                                            .build();
        ctx.attr(Attr.ATTR_KEY).set(new Attr("line"));

        final RequestLog log = ctx.log();
        final RequestLogBuilder logBuilder = ctx.logBuilder();

        logBuilder.endRequest();
        assertThat(ctx.log().isAvailable(RequestLogAvailability.REQUEST_END)).isTrue();
        assertThat(log.isAvailable(RequestLogAvailability.REQUEST_END)).isTrue();

        logBuilder.responseHeaders(HttpHeaders.of(HttpStatus.OK)
                                              .addObject(HttpHeaderNames.CONTENT_TYPE,
                                                         MediaType.PLAIN_TEXT_UTF_8));
        logBuilder.responseLength(1024);
        logBuilder.endResponse();

        final String localhostAddress = NetUtil.LOCALHOST.getHostAddress();
        final String timestamp = defaultDateTimeFormatter.format(ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(requestStartTimeMillis), defaultZoneId));

        String message;
        List<AccessLogComponent> format;

        message = AccessLogger.format(AccessLogFormats.COMMON, log);
        assertThat(message).isEqualTo(
                localhostAddress + " - - " + timestamp + " \"GET /armeria/log h2c\" 200 1024");

        message = AccessLogger.format(AccessLogFormats.COMBINED, log);
        assertThat(message).isEqualTo(
                localhostAddress + " - - " + timestamp + " \"GET /armeria/log h2c\" 200 1024" +
                " \"http://log.example.com\" \"armeria/x.y.z\" \"a=1;b=2\"");

        // Check conditions with custom formats.
        format = AccessLogFormats.parseCustom(
                "%h %l %u %t \"%r\" %s %b \"%200,302{Referer}i\" \"%!200,304{User-Agent}i\"" +
                " some-text %{Non-Existing-Header}i");

        message = AccessLogger.format(format, log);
        assertThat(message).isEqualTo(
                localhostAddress + " - - " + timestamp + " \"GET /armeria/log h2c\" 200 1024" +
                " \"http://log.example.com\" \"-\" some-text -");

        format = AccessLogFormats.parseCustom(
                "%h %l %u %t \"%r\" %s %b \"%!200,302{Referer}i\" \"%200,304{User-Agent}i\"" +
                " some-text %{Non-Existing-Header}i");
        message = AccessLogger.format(format, log);
        assertThat(message).isEqualTo(
                localhostAddress + " - - " + timestamp + " \"GET /armeria/log h2c\" 200 1024" +
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

        format = AccessLogFormats.parseCustom("%{content-type}o");
        assertThat(AccessLogger.format(format, log)).isEqualTo(MediaType.PLAIN_TEXT_UTF_8.toString());
    }

    @Test
    public void logClientAddress() throws Exception {
        final InetSocketAddress remote = new InetSocketAddress(InetAddress.getByName("10.1.0.1"), 5000);
        final ServiceRequestContext ctx =
                ServiceRequestContextBuilder.of(HttpRequest.of(HttpMethod.GET, "/"))
                                            .remoteAddress(remote)
                                            .clientAddress(InetAddress.getByName("10.0.0.1"))
                                            .build();

        List<AccessLogComponent> format;

        // Client IP address
        format = AccessLogFormats.parseCustom("%a");
        assertThat(AccessLogger.format(format, ctx.log())).isEqualTo("10.0.0.1");

        // Remote IP address of a channel
        format = AccessLogFormats.parseCustom("%{c}a");
        assertThat(AccessLogger.format(format, ctx.log())).isEqualTo("10.1.0.1");
    }

    @Test
    public void requestLogAvailabilityException() {
        final String expectedLogMessage = "\"GET /armeria/log#rpcMethod h2\" 200 1024";

        final ServiceRequestContext ctx = ServiceRequestContextBuilder.of(
                HttpRequest.of(HttpHeaders.of(HttpMethod.GET, "/armeria/log")
                                          .add(HttpHeaderNames.USER_AGENT, "armeria/x.y.z")
                                          .add(HttpHeaderNames.REFERER, "http://log.example.com")
                                          .add(HttpHeaderNames.COOKIE, "a=1;b=2"))).build();
        final RequestLog log = ctx.log();
        final RequestLogBuilder logBuilder = ctx.logBuilder();

        // AccessLogger#format will be called after response is finished.
        log.addListener(l -> assertThat(AccessLogger.format(AccessLogFormats.COMMON, l))
                .endsWith(expectedLogMessage), RequestLogAvailability.COMPLETE);

        // RequestLogAvailabilityException will be raised inside AccessLogger#format before injecting each
        // component to RequestLog. So we cannot get the expected log message here.
        assertThat(AccessLogger.format(AccessLogFormats.COMMON, log)).doesNotEndWith(expectedLogMessage);
        logBuilder.requestContent(new DefaultRpcRequest(Object.class, "rpcMethod"), null);
        assertThat(AccessLogger.format(AccessLogFormats.COMMON, log)).doesNotEndWith(expectedLogMessage);
        logBuilder.endRequest();
        assertThat(AccessLogger.format(AccessLogFormats.COMMON, log)).doesNotEndWith(expectedLogMessage);
        logBuilder.responseHeaders(HttpHeaders.of(HttpStatus.OK));
        assertThat(AccessLogger.format(AccessLogFormats.COMMON, log)).doesNotEndWith(expectedLogMessage);
        logBuilder.responseLength(1024);
        assertThat(AccessLogger.format(AccessLogFormats.COMMON, log)).doesNotEndWith(expectedLogMessage);
        logBuilder.endResponse();
    }

    @Test
    public void requestLogComponent() {
        final ServiceRequestContext ctx =
                ServiceRequestContextBuilder.of(HttpRequest.of(HttpMethod.GET, "/armeria/log"))
                                            .requestStartTime(requestStartTimeNanos, requestStartTimeMicros)
                                            .build();

        final RequestLog log = ctx.log();
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
    public void requestLogWithEmptyCause() {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

        final RequestLog log = ctx.log();
        final RequestLogBuilder logBuilder = ctx.logBuilder();

        final List<AccessLogComponent> format =
                AccessLogFormats.parseCustom("%{requestCause}L %{responseCause}L");

        logBuilder.endRequest();
        logBuilder.endResponse();

        assertThat(AccessLogger.format(format, log)).isEqualTo("- -");
    }

    @Test
    public void timestamp() {
        final ServiceRequestContext ctx =
                ServiceRequestContextBuilder.of(HttpRequest.of(HttpMethod.GET, "/"))
                                            .requestStartTime(requestStartTimeNanos, requestStartTimeMicros)
                                            .build();
        final RequestLog log = ctx.log();

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
