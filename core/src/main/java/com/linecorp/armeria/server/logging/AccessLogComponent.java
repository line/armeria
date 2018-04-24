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

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.logging.RequestLog;

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

    static AccessLogComponent ofPredefinedCommon(AccessLogType type) {
        return new CommonComponent(type, type == AccessLogType.REQUEST_LINE, null);
    }

    static AccessLogComponent ofQuotedRequestHeader(AsciiString headerName) {
        return new RequestHeaderComponent(headerName, true, null);
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
     * An abstract class to support a condition whether a log component is applied or not.
     */
    abstract class ResponseHeaderConditional implements AccessLogComponent {

        @Nullable
        private final Function<HttpHeaders, Boolean> condition;

        protected ResponseHeaderConditional(@Nullable Function<HttpHeaders, Boolean> condition) {
            this.condition = condition;
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
    }

    /**
     * Common log components of a log message.
     */
    class CommonComponent extends ResponseHeaderConditional {

        @VisibleForTesting
        static final DateTimeFormatter dateTimeFormatter =
                DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);
        @VisibleForTesting
        static final ZoneId defaultZoneId = ZoneId.systemDefault();

        static boolean isSupported(AccessLogType type) {
            switch (type) {
                case REMOTE_HOST:
                case RFC931:
                case AUTHENTICATED_USER:
                case REQUEST_TIMESTAMP:
                case REQUEST_LINE:
                case RESPONSE_STATUS_CODE:
                case RESPONSE_LENGTH:
                    return true;
                default:
                    return false;
            }
        }

        private final AccessLogType type;
        private final boolean addQuote;

        CommonComponent(AccessLogType type, boolean addQuote,
                        @Nullable Function<HttpHeaders, Boolean> condition) {
            super(condition);
            checkArgument(isSupported(requireNonNull(type, "type")),
                          "Type '" + type + "' is not acceptable by " +
                          CommonComponent.class.getName());
            this.type = type;
            this.addQuote = addQuote;
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

                case REQUEST_TIMESTAMP:
                    return dateTimeFormatter.format(ZonedDateTime.ofInstant(
                            Instant.ofEpochMilli(log.requestStartTimeMillis()), defaultZoneId));
                case REQUEST_LINE:
                    final StringBuilder requestLine = new StringBuilder();

                    final HttpHeaders headers = log.requestHeaders();
                    requestLine.append(headers.method().name())
                               .append(' ')
                               .append(headers.path());

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
                    return log.responseHeaders().status().code();

                case RESPONSE_LENGTH:
                    return log.responseLength();
            }
            return null;
        }

        @Override
        public boolean addQuote() {
            return addQuote;
        }
    }

    /**
     * A request header component of a log message.
     */
    class RequestHeaderComponent extends ResponseHeaderConditional {

        static boolean isSupported(AccessLogType type) {
            return type == AccessLogType.REQUEST_HEADER;
        }

        private final AsciiString headerName;
        private final boolean addQuote;

        RequestHeaderComponent(AsciiString headerName, boolean addQuote,
                               @Nullable Function<HttpHeaders, Boolean> condition) {
            super(condition);
            this.headerName = requireNonNull(headerName, "headerName");
            this.addQuote = addQuote;
        }

        @VisibleForTesting
        AsciiString headerName() {
            return headerName;
        }

        @Override
        public Object getMessage0(RequestLog log) {
            return log.requestHeaders().get(headerName);
        }

        @Override
        public boolean addQuote() {
            return addQuote;
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
        private final boolean addQuote;

        AttributeComponent(String attributeName, Function<Object, String> stringifer, boolean addQuote,
                           @Nullable Function<HttpHeaders, Boolean> condition) {
            super(condition);
            key = AttributeKey.valueOf(requireNonNull(attributeName, "attributeName"));
            this.stringifer = requireNonNull(stringifer, "stringifer");
            this.addQuote = addQuote;
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

        @Override
        public boolean addQuote() {
            return addQuote;
        }
    }
}
