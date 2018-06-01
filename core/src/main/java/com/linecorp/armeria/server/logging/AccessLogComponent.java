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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;
import static org.reflections.ReflectionUtils.getFields;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.util.Exceptions;

import io.netty.util.AsciiString;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

/**
 * An access log component to generate a log message.
 */
@FunctionalInterface
interface AccessLogComponent {

    /**
     * Returns a part of a log message.
     */
    @Nullable
    Object getMessage(RequestLog log);

    /**
     * Returns whether adding quotes between a log message.
     */
    default boolean addQuote() {
        return false;
    }

    static AccessLogComponent ofText(String text) {
        return new TextComponent(text);
    }

    static AccessLogComponent ofDefaultRequestTimestamp() {
        return new TimestampComponent(false, null);
    }

    static AccessLogComponent ofPredefinedCommon(AccessLogType type) {
        return new CommonComponent(type, type == AccessLogType.REQUEST_LINE, null);
    }

    static AccessLogComponent ofQuotedRequestHeader(AsciiString headerName) {
        return new HttpHeaderComponent(AccessLogType.REQUEST_HEADER,
                                       headerName, true, null);
    }

    /**
     * A text component of a log message.
     */
    class TextComponent implements AccessLogComponent {

        static boolean isSupported(AccessLogType type) {
            return type == AccessLogType.TEXT;
        }

        private final String text;

        TextComponent(String text) {
            this.text = requireNonNull(text, "text");
        }

        @Override
        public Object getMessage(RequestLog log) {
            return text;
        }
    }

    /**
     * A timestamp component of a log message.
     */
    class TimestampComponent implements AccessLogComponent {

        @VisibleForTesting
        static final DateTimeFormatter defaultDateTimeFormatter =
                DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);
        @VisibleForTesting
        static final ZoneId defaultZoneId = ZoneId.systemDefault();

        private static final Map<String, DateTimeFormatter> predefinedFormatters =
                getFields(DateTimeFormatter.class, field -> {
                    final int m = field.getModifiers();
                    // public static final DateTimeFormatter ...
                    return Modifier.isPublic(m) && Modifier.isStatic(m) && Modifier.isFinal(m) &&
                           (field.getType() == DateTimeFormatter.class);
                }).stream().collect(Collectors.toMap(Field::getName, f -> {
                    try {
                        return (DateTimeFormatter) f.get(null);
                    } catch (Throwable cause) {
                        // Should not reach here.
                        throw new Error(cause);
                    }
                }));

        static boolean isSupported(AccessLogType type) {
            return type == AccessLogType.REQUEST_TIMESTAMP;
        }

        private final boolean addQuote;
        private final DateTimeFormatter formatter;

        TimestampComponent(boolean addQuote, @Nullable String variable) {
            this.addQuote = addQuote;
            formatter = findFormatter(variable);
        }

        @Nullable
        @Override
        public Object getMessage(RequestLog log) {
            return formatter.format(ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(log.requestStartTimeMillis()), defaultZoneId));
        }

        @Override
        public boolean addQuote() {
            return addQuote;
        }

        static DateTimeFormatter findFormatter(@Nullable String variable) {
            if (variable == null) {
                return defaultDateTimeFormatter;
            }

            final DateTimeFormatter predefined = predefinedFormatters.get(variable);
            if (predefined != null) {
                return predefined;
            }

            // User-defined pattern.
            try {
                return DateTimeFormatter.ofPattern(variable, Locale.ENGLISH);
            } catch (Throwable cause) {
                throw new IllegalArgumentException("unexpected date/time format variable: " +
                                                   variable, cause);
            }
        }
    }

    /**
     * An abstract class to support a condition whether a log component is applied or not.
     */
    abstract class ResponseHeaderConditional implements AccessLogComponent {

        @Nullable
        private final Function<HttpHeaders, Boolean> condition;
        private final boolean addQuote;

        protected ResponseHeaderConditional(@Nullable Function<HttpHeaders, Boolean> condition,
                                            boolean addQuote) {
            this.condition = condition;
            this.addQuote = addQuote;
        }

        @Nullable
        @VisibleForTesting
        Function<HttpHeaders, Boolean> condition() {
            return condition;
        }

        @Nullable
        @Override
        public final Object getMessage(RequestLog log) {
            if (condition != null &&
                !condition.apply(log.responseHeaders())) {
                return null;
            }
            return getMessage0(log);
        }

        @Nullable
        abstract Object getMessage0(RequestLog log);

        @Override
        public boolean addQuote() {
            return addQuote;
        }
    }

    /**
     * Common log components of a log message.
     */
    class CommonComponent extends ResponseHeaderConditional {

        static boolean isSupported(AccessLogType type) {
            switch (type) {
                case REMOTE_HOST:
                case RFC931:
                case AUTHENTICATED_USER:
                case REQUEST_LINE:
                case RESPONSE_STATUS_CODE:
                case RESPONSE_LENGTH:
                    return true;
                default:
                    return false;
            }
        }

        private final AccessLogType type;

        CommonComponent(AccessLogType type, boolean addQuote,
                        @Nullable Function<HttpHeaders, Boolean> condition) {
            super(condition, addQuote);
            checkArgument(isSupported(requireNonNull(type, "type")),
                          "Type '" + type + "' is not acceptable by " +
                          CommonComponent.class.getName());
            this.type = type;
        }

        @Nullable
        @Override
        public Object getMessage0(RequestLog log) {
            switch (type) {
                case REMOTE_HOST:
                    final SocketAddress ra = log.context().remoteAddress();
                    return ra instanceof InetSocketAddress ? ((InetSocketAddress) ra).getHostString() : null;

                case RFC931:
                case AUTHENTICATED_USER:
                    // We do not support these kinds of log types now.
                    return null;

                case REQUEST_LINE:
                    final StringBuilder requestLine = new StringBuilder();

                    requestLine.append(log.method())
                               .append(' ')
                               .append(log.requestHeaders().path());

                    final Object requestContent = log.requestContent();
                    if (requestContent instanceof RpcRequest) {
                        requestLine.append('#')
                                   .append(((RpcRequest) requestContent).method());
                    }

                    requestLine.append(' ')
                               .append(firstNonNull(log.sessionProtocol(),
                                                    log.context().sessionProtocol()).uriText());
                    return requestLine.toString();

                case RESPONSE_STATUS_CODE:
                    return log.statusCode();

                case RESPONSE_LENGTH:
                    return log.responseLength();
            }
            return null;
        }
    }

    /**
     * An HTTP header component of a log message.
     */
    class HttpHeaderComponent extends ResponseHeaderConditional {

        static boolean isSupported(AccessLogType type) {
            return type == AccessLogType.REQUEST_HEADER ||
                   type == AccessLogType.RESPONSE_HEADER;
        }

        private final AsciiString headerName;
        private final Function<RequestLog, HttpHeaders> httpHeaders;

        HttpHeaderComponent(AccessLogType logType, AsciiString headerName, boolean addQuote,
                            @Nullable Function<HttpHeaders, Boolean> condition) {
            super(condition, addQuote);
            this.headerName = requireNonNull(headerName, "headerName");
            if (logType == AccessLogType.REQUEST_HEADER) {
                httpHeaders = RequestLog::requestHeaders;
            } else {
                assert logType == AccessLogType.RESPONSE_HEADER : logType.name();
                httpHeaders = RequestLog::responseHeaders;
            }
        }

        @VisibleForTesting
        AsciiString headerName() {
            return headerName;
        }

        @Override
        public Object getMessage0(RequestLog log) {
            return httpHeaders.apply(log).get(headerName);
        }
    }

    /**
     * An attribute component of a log message.
     */
    class AttributeComponent extends ResponseHeaderConditional {

        static boolean isSupported(AccessLogType type) {
            return type == AccessLogType.ATTRIBUTE;
        }

        private final AttributeKey<?> key;
        private final Function<Object, String> stringifer;

        AttributeComponent(String attributeName, Function<Object, String> stringifer, boolean addQuote,
                           @Nullable Function<HttpHeaders, Boolean> condition) {
            super(condition, addQuote);
            key = AttributeKey.valueOf(requireNonNull(attributeName, "attributeName"));
            this.stringifer = requireNonNull(stringifer, "stringifer");
        }

        @VisibleForTesting
        AttributeKey<?> key() {
            return key;
        }

        @Nullable
        @Override
        Object getMessage0(RequestLog log) {
            final Attribute<?> value = log.context().attr(key);
            return value != null ? stringifer.apply(value.get()) : null;
        }
    }

    /**
     * A {@link RequestLog} component of a log message.
     */
    class RequestLogComponent extends ResponseHeaderConditional {

        static boolean isSupported(AccessLogType type) {
            return type == AccessLogType.REQUEST_LOG;
        }

        private final Function<RequestLog, Object> resolver;

        RequestLogComponent(String variable, boolean addQuote,
                            @Nullable Function<HttpHeaders, Boolean> condition) {
            super(condition, addQuote);
            resolver = findResolver(requireNonNull(variable, "variable"));
        }

        @Nullable
        @Override
        Object getMessage0(RequestLog log) {
            return resolver.apply(log);
        }

        @Nullable
        private static String handleThrowable(@Nullable Throwable cause) {
            if (cause == null) {
                return null;
            }
            cause = Exceptions.peel(cause);
            final String message = cause.getMessage();
            return message != null ? cause.getClass().getSimpleName() + ": " + message
                                   : cause.getClass().getSimpleName();
        }

        private static Function<RequestLog, Object> findResolver(String variable) {
            // The same order as methods in the RequestLog interface.
            switch (variable) {
                case "method":
                    return RequestLog::method;
                case "path":
                    return RequestLog::path;
                case "query":
                    return RequestLog::query;

                case "requestStartTimeMillis":
                    return RequestLog::requestStartTimeMillis;
                case "requestEndTimeMillis":
                    return log -> Instant.ofEpochMilli(log.requestStartTimeMillis())
                                         .plusNanos(log.requestDurationNanos()).toEpochMilli();
                case "requestDurationMillis":
                    return log -> Duration.ofNanos(log.requestDurationNanos()).toMillis();
                case "requestDurationNanos":
                    return RequestLog::requestDurationNanos;
                case "requestLength":
                    return RequestLog::requestLength;
                case "requestCause":
                    return log -> handleThrowable(log.requestCause());

                case "responseStartTimeMillis":
                    return RequestLog::responseStartTimeMillis;
                case "responseEndTimeMillis":
                    return log -> Instant.ofEpochMilli(log.responseStartTimeMillis())
                                         .plusNanos(log.responseDurationNanos()).toEpochMilli();
                case "responseDurationMillis":
                    return log -> Duration.ofNanos(log.responseDurationNanos()).toMillis();
                case "responseDurationNanos":
                    return RequestLog::responseDurationNanos;
                case "responseLength":
                    return RequestLog::responseLength;
                case "responseCause":
                    return log -> handleThrowable(log.responseCause());

                case "totalDurationMillis":
                    return log -> Duration.ofNanos(log.totalDurationNanos()).toMillis();
                case "totalDurationNanos":
                    return RequestLog::totalDurationNanos;

                case "sessionProtocol":
                    return RequestLog::sessionProtocol;
                case "serializationFormat":
                    return RequestLog::serializationFormat;
                case "scheme":
                    return RequestLog::scheme;
                case "host":
                    return log -> {
                        final String authority = log.responseHeaders().authority();
                        if ("?".equals(authority)) {
                            final InetSocketAddress remoteAddr = log.context().remoteAddress();
                            assert remoteAddr != null;
                            return remoteAddr.getHostString();
                        }
                        return authority;
                    };
                case "status":
                    return RequestLog::status;
                case "statusCode":
                    return RequestLog::statusCode;

                default:
                    throw new IllegalArgumentException("unexpected request log variable: " + variable);
            }
        }
    }
}
