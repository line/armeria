/*
 * Copyright 2022 LINE Corporation
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
package com.linecorp.armeria.server.websocket;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.websocket.WebSocketCloseStatus;
import com.linecorp.armeria.common.websocket.WebSocketFrameType;
import com.linecorp.armeria.internal.common.websocket.WebSocketUtil;
import com.linecorp.armeria.internal.server.websocket.DefaultWebSocketService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceOptions;

/**
 * Builds a {@link WebSocketService}.
 * This service has the different default configs from a normal {@link HttpService}. Here are the differences:
 * <ul>
 *   <li>{@link ServiceConfig#requestTimeoutMillis()} is
 *       {@value WebSocketUtil#DEFAULT_REQUEST_RESPONSE_TIMEOUT_MILLIS}.</li>
 *   <li>{@link ServiceConfig#maxRequestLength()} is
 *       {@value WebSocketUtil#DEFAULT_MAX_REQUEST_RESPONSE_LENGTH}.</li>
 *   <li>{@link ServiceConfig#requestAutoAbortDelayMillis()} is
 *       {@value WebSocketUtil#DEFAULT_REQUEST_AUTO_ABORT_DELAY_MILLIS}.</li>
 * </ul>
 */
@UnstableApi
public final class WebSocketServiceBuilder {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketServiceBuilder.class);

    private static final String ANY_ORIGIN = "*";

    static final int DEFAULT_MAX_FRAME_PAYLOAD_LENGTH = 65535; // 64 * 1024 -1

    static final ServiceOptions DEFAULT_OPTIONS = ServiceOptions
            .builder()
            .requestTimeoutMillis(WebSocketUtil.DEFAULT_REQUEST_RESPONSE_TIMEOUT_MILLIS)
            .maxRequestLength(WebSocketUtil.DEFAULT_MAX_REQUEST_RESPONSE_LENGTH)
            .requestAutoAbortDelayMillis(WebSocketUtil.DEFAULT_REQUEST_AUTO_ABORT_DELAY_MILLIS)
            .build();

    private final WebSocketServiceHandler handler;

    private int maxFramePayloadLength = DEFAULT_MAX_FRAME_PAYLOAD_LENGTH;
    private boolean allowMaskMismatch;
    private Set<String> subprotocols = ImmutableSet.of();
    @Nullable
    private Set<String> allowedOrigins;
    @Nullable
    private Predicate<? super String> originPredicate;
    private boolean aggregateContinuation;
    @Nullable
    private HttpService fallbackService;
    private ServiceOptions serviceOptions = DEFAULT_OPTIONS;

    WebSocketServiceBuilder(WebSocketServiceHandler handler) {
        this.handler = requireNonNull(handler, "handler");
    }

    /**
     * Sets the maximum length of a frame's payload. If the size of a payload data exceeds the value,
     * {@link WebSocketCloseStatus#MESSAGE_TOO_BIG} is sent to the peer.
     * {@value DEFAULT_MAX_FRAME_PAYLOAD_LENGTH} is used by default.
     */
    public WebSocketServiceBuilder maxFramePayloadLength(int maxFramePayloadLength) {
        checkArgument(maxFramePayloadLength > 0,
                      "maxFramePayloadLength: %s (expected: > 0)", maxFramePayloadLength);
        this.maxFramePayloadLength = maxFramePayloadLength;
        return this;
    }

    /**
     * Sets whether the decoder allows to loosen the masking requirement on received frames.
     * It's not allowed by default.
     */
    public WebSocketServiceBuilder allowMaskMismatch(boolean allowMaskMismatch) {
        this.allowMaskMismatch = allowMaskMismatch;
        return this;
    }

    /**
     * Sets the subprotocols to use with the WebSocket Protocol.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-1.9">
     *     Subprotocols Using the WebSocket Protocol</a>
     */
    public WebSocketServiceBuilder subprotocols(String... subprotocols) {
        return subprotocols(ImmutableSet.copyOf(requireNonNull(subprotocols, "subprotocols")));
    }

    /**
     * Sets the subprotocols to use with the WebSocket Protocol.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-1.9">
     *     Subprotocols Using the WebSocket Protocol</a>
     */
    public WebSocketServiceBuilder subprotocols(Iterable<String> subprotocols) {
        this.subprotocols = ImmutableSet.copyOf(requireNonNull(subprotocols, "subprotocols"));
        return this;
    }

    /**
     * Sets whether to aggregate the subsequent continuation frames of the incoming
     * {@link WebSocketFrameType#TEXT} or {@link WebSocketFrameType#BINARY} frame into a single
     * {@link WebSocketFrameType#TEXT} or {@link WebSocketFrameType#BINARY} frame.
     * If the length of the aggregated frames exceeds the {@link #maxFramePayloadLength(int)},
     * a close frame with the status {@link WebSocketCloseStatus#MESSAGE_TOO_BIG} is sent to the peer.
     * Note that enabling this feature may lead to increased memory usage, so use it with caution.
     */
    public WebSocketServiceBuilder aggregateContinuation(boolean aggregateContinuation) {
        this.aggregateContinuation = aggregateContinuation;
        return this;
    }

    /**
     * Sets the allowed origins. The same-origin is allowed by default.
     * Specify {@value ANY_ORIGIN} to allow any origins.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-10.2">Origin Considerations</a>
     */
    public WebSocketServiceBuilder allowedOrigins(String... allowedOrigins) {
        return allowedOrigins(ImmutableSet.copyOf(requireNonNull(allowedOrigins, "allowedOrigins")));
    }

    /**
     * Sets the allowed origins. The same-origin is allowed by default.
     * Specify {@value ANY_ORIGIN} to allow any origins.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-10.2">Origin Considerations</a>
     */
    public WebSocketServiceBuilder allowedOrigins(Iterable<String> allowedOrigins) {
        checkState(originPredicate == null, "allowedOrigins and originPredicate are mutually exclusive.");
        this.allowedOrigins = validateOrigins(allowedOrigins);
        return this;
    }

    /**
     * Sets the predicate that evaluates whether an origin is allowed. The same-origin is allowed by default.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-10.2">Origin Considerations</a>
     */
    public WebSocketServiceBuilder allowedOrigin(Predicate<? super String> predicate) {
        checkState(allowedOrigins == null, "allowedOrigins and originPredicate are mutually exclusive.");
        originPredicate = requireNonNull(predicate, "predicate");
        return this;
    }

    /**
     * Sets the regex pattern to evaluate whether an origin is allowed. The same-origin is allowed by default.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-10.2">Origin Considerations</a>
     */
    public WebSocketServiceBuilder allowedOrigin(String regex) {
        return allowedOrigin(Pattern.compile(requireNonNull(regex, "regex")));
    }

    /**
     * Sets the regex pattern to evaluate whether an origin is allowed. The same-origin is allowed by default.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-10.2">Origin Considerations</a>
     */
    public WebSocketServiceBuilder allowedOrigin(Pattern regex) {
        return allowedOrigin(requireNonNull(regex, "regex").asPredicate());
    }

    private static Set<String> validateOrigins(Iterable<String> allowedOrigins) {
        //TODO(minwoox): Dedup the same logic in cors service.
        final Set<String> copied = ImmutableSet.copyOf(requireNonNull(allowedOrigins, "allowedOrigins"))
                                               .stream()
                                               .map(Ascii::toLowerCase)
                                               .collect(toImmutableSet());
        checkArgument(!copied.isEmpty(), "allowedOrigins is empty. (expected: non-empty)");
        if (copied.contains(ANY_ORIGIN)) {
            if (copied.size() > 1) {
                logger.warn("Any origin (*) has been already included. Other origins ({}) will be ignored.",
                            copied.stream()
                                  .filter(c -> !ANY_ORIGIN.equals(c))
                                  .collect(Collectors.joining(",")));
            }
        }
        return copied;
    }

    /**
     * Sets the {@link ServiceOptions} for the {@link WebSocketService}.
     * If not set, {@link WebSocketService#options()} is used.
     */
    public WebSocketServiceBuilder serviceOptions(ServiceOptions serviceOptions) {
        requireNonNull(serviceOptions, "serviceOptions");
        this.serviceOptions = serviceOptions;
        return this;
    }

    /**
     * Sets the fallback {@link HttpService} to use when the request is not a valid WebSocket upgrade request.
     * This is useful when you want to serve both WebSocket and HTTP requests at the same path.
     */
    public WebSocketServiceBuilder fallbackService(HttpService fallbackService) {
        this.fallbackService = requireNonNull(fallbackService, "fallbackService");
        checkArgument(!(fallbackService instanceof WebSocketService),
                      "fallbackService must not be a WebSocketService.");
        return this;
    }

    /**
     * Returns a newly-created {@link WebSocketService} with the properties set so far.
     */
    public WebSocketService build() {
        final boolean allowAnyOrigin;
        final Predicate<? super String> originPredicate;
        if (allowedOrigins != null) {
            allowAnyOrigin = allowedOrigins.contains(ANY_ORIGIN);
            originPredicate = allowedOrigins::contains;
        } else {
            allowAnyOrigin = false;
            originPredicate = this.originPredicate;
        }
        return new DefaultWebSocketService(handler, fallbackService, maxFramePayloadLength, allowMaskMismatch,
                                           subprotocols, allowAnyOrigin, originPredicate, aggregateContinuation,
                                           serviceOptions);
    }
}
