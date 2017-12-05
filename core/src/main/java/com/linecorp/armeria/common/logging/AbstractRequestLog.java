/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.common.logging;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.REQUEST_CONTENT;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.REQUEST_END;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.REQUEST_HEADERS;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.REQUEST_START;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.RESPONSE_CONTENT;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.RESPONSE_END;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.RESPONSE_HEADERS;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.RESPONSE_START;
import static com.linecorp.armeria.common.logging.RequestLogAvailability.SCHEME;
import static java.util.Objects.requireNonNull;

import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.util.TextFormatter;

public abstract class AbstractRequestLog implements RequestLog, RequestLogBuilder {

    private static final int STRING_BUILDER_CAPACITY = 512;

    private final RequestContext ctx;

    private volatile int requestStrFlags = -1;
    private volatile int responseStrFlags = -1;
    private String requestStr;
    private String responseStr;

    AbstractRequestLog(RequestContext ctx) {
        this.ctx = requireNonNull(ctx, "ctx");
    }

    @Override
    public Set<RequestLogAvailability> availabilities() {
        return RequestLogAvailabilitySet.of(flags());
    }

    @Override
    public boolean isAvailable(RequestLogAvailability availability) {
        return isAvailable(availability.getterFlags());
    }

    @Override
    public boolean isAvailable(RequestLogAvailability... availabilities) {
        return isAvailable(RequestLogAvailability.getterFlags(availabilities));
    }

    @Override
    public boolean isAvailable(Iterable<RequestLogAvailability> availabilities) {
        return isAvailable(RequestLogAvailability.getterFlags(availabilities));
    }

    final boolean isAvailable(int interestedFlags) {
        return (flags() & interestedFlags) == interestedFlags;
    }

    private static boolean isAvailable(int flags, RequestLogAvailability availability) {
        final int interestedFlags = availability.getterFlags();
        return (flags & interestedFlags) == interestedFlags;
    }

    @Override
    public void addListener(RequestLogListener listener, RequestLogAvailability availability) {
        requireNonNull(availability, "availability");
        addListener(listener, ImmutableList.of(availability));
    }

    @Override
    public void addListener(RequestLogListener listener, RequestLogAvailability... availabilities) {
        requireNonNull(availabilities, "availabilities");
        addListener(listener, Stream.of(availabilities).collect(toImmutableList()));
    }

    @Override
    public void addListener(RequestLogListener listener, Iterable<RequestLogAvailability> availabilities) {
        requireNonNull(listener, "listener");
        requireNonNull(availabilities, "availabilities");
        addListener(new ListenerEntry(listener, RequestLogAvailability.getterFlags(availabilities)));
    }

    abstract void addListener(ListenerEntry listenerEntry);

    @Override
    public RequestContext context() {
        return ctx;
    }

    @Override
    public String toStringRequestOnly() {
        return toStringRequestOnly(Function.identity(), Function.identity());
    }

    @Override
    public String toStringRequestOnly(Function<HttpHeaders, HttpHeaders> headersSanitizer,
                                       Function<Object, Object> contentSanitizer) {
        final int requestFlags = flags() & 0xFFFF; // Only interested in the bits related with request.
        if (requestStrFlags == requestFlags) {
            return requestStr;
        }

        final StringBuilder buf = new StringBuilder(STRING_BUILDER_CAPACITY);
        buf.append('{');
        if (isAvailable(requestFlags, REQUEST_START)) {
            buf.append("startTime=");
            TextFormatter.appendEpoch(buf, requestStartTimeMillis());

            if (isAvailable(requestFlags, REQUEST_END)) {
                buf.append(", length=");
                TextFormatter.appendSize(buf, requestLength());
                buf.append(", duration=");
                TextFormatter.appendElapsed(buf, requestDurationNanos());
                if (requestCause() != null) {
                    buf.append(", cause=").append(requestCause());
                }
            }

            buf.append(", scheme=");
            if (isAvailable(requestFlags, SCHEME)) {
                buf.append(scheme().uriText());
            } else {
                buf.append(SerializationFormat.UNKNOWN.uriText())
                   .append('+')
                   .append(sessionProtocol().uriText());
            }

            buf.append(", host=").append(host());

            if (isAvailable(requestFlags, REQUEST_HEADERS) && requestHeaders() != null) {
                buf.append(", headers=").append(headersSanitizer.apply(requestHeaders()));
            }

            if (isAvailable(requestFlags, REQUEST_CONTENT) && requestContent() != null) {
                buf.append(", content=").append(contentSanitizer.apply(requestContent()));
            }
        }
        buf.append('}');

        requestStr = buf.toString();
        requestStrFlags = requestFlags;

        return requestStr;
    }

    @Override
    public String toStringResponseOnly() {
        return toStringResponseOnly(Function.identity(), Function.identity());
    }

    @Override
    public String toStringResponseOnly(Function<HttpHeaders, HttpHeaders> headersSanitizer,
                                        Function<Object, Object> contentSanitizer) {
        final int responseFlags = flags() & 0xFFFF0000; // Only interested in the bits related with response.
        if (responseStrFlags == responseFlags) {
            return responseStr;
        }

        final StringBuilder buf = new StringBuilder(STRING_BUILDER_CAPACITY);
        buf.append('{');
        if (isAvailable(responseFlags, RESPONSE_START)) {
            buf.append("startTime=");
            TextFormatter.appendEpoch(buf, responseStartTimeMillis());

            if (isAvailable(responseFlags, RESPONSE_END)) {
                buf.append(", length=");
                TextFormatter.appendSize(buf, responseLength());
                buf.append(", duration=");
                TextFormatter.appendElapsed(buf, responseDurationNanos());
                if (responseCause() != null) {
                    buf.append(", cause=").append(responseCause());
                }
            }

            if (isAvailable(responseFlags, RESPONSE_HEADERS) && responseHeaders() != null) {
                buf.append(", headers=").append(headersSanitizer.apply(responseHeaders()));
            }

            if (isAvailable(responseFlags, RESPONSE_CONTENT) && responseContent() != null) {
                buf.append(", content=").append(contentSanitizer.apply(responseContent()));
            }
        }
        buf.append('}');

        responseStr = buf.toString();
        responseStrFlags = responseFlags;

        return responseStr;
    }

    @Override
    public String toString() {
        return toString(toStringRequestOnly(), toStringResponseOnly());
    }

    String toString(String req, String res) {
        final StringBuilder buf = new StringBuilder(5 + req.length() + 6 + res.length() + 1);
        return buf.append("{req=")  // 5 chars
                  .append(req)
                  .append(", res=") // 6 chars
                  .append(res)
                  .append('}')      // 1 char
                  .toString();
    }

    static class ListenerEntry {
        private final RequestLogListener listener;
        private final int interestedFlags;

        ListenerEntry(RequestLogListener listener, int interestedFlags) {
            this.listener = listener;
            this.interestedFlags = interestedFlags;
        }

        public RequestLogListener listener() {
            return listener;
        }

        public int interestedFlags() {
            return interestedFlags;
        }
    }
}
