/*
 * Copyright 2015 LINE Corporation
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
package com.linecorp.armeria.client.logging;

import static com.linecorp.armeria.common.util.UnitFormatter.elapsed;
import static java.util.Objects.requireNonNull;

import java.lang.reflect.Method;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientCodec;
import com.linecorp.armeria.client.DecoratingClientCodec;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.Ticker;
import com.linecorp.armeria.common.util.UnitFormatter;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Promise;

/**
 * Decorates a {@link ClientCodec} to log invocation requests and responses.
 */
final class LoggingClientCodec extends DecoratingClientCodec {

    private static final Logger logger = LoggerFactory.getLogger(LoggingClientCodec.class);

    private static final AttributeKey<Long> START_TIME_NANOS =
            AttributeKey.valueOf(LoggingClientCodec.class, "START_TIME_NANOS");

    private final Ticker ticker;

    /**
     * Creates a new instance that decorates the specified {@code codec}.
     *
     * @param ticker an alternative {@link Ticker}
     */
    LoggingClientCodec(ClientCodec codec, Ticker ticker) {
        super(codec);
        this.ticker = requireNonNull(ticker);
    }

    @Override
    public <T> void prepareRequest(Method method, Object[] args, Promise<T> resultPromise) {
        delegate().prepareRequest(method, args, resultPromise);
    }

    @Override
    public EncodeResult encodeRequest(
            Channel channel, SessionProtocol sessionProtocol, Method method, Object[] args) {

        final EncodeResult result = delegate().encodeRequest(channel, sessionProtocol, method, args);
        if (result.isSuccess()) {
            final ServiceInvocationContext ctx = result.invocationContext();
            final Logger logger = ctx.logger();
            if (logger.isInfoEnabled()) {
                logger.info("Request: {} ({})", ctx.params(), contentSize(result.content()));
                ctx.attr(START_TIME_NANOS).set(ticker.read());
            }
        } else {
            final Optional<Scheme> scheme = result.encodedScheme();
            final Optional<String> hostname = result.encodedHost();
            final Optional<String> path = result.encodedPath();

            logger.warn("{}[{}://{}{}#{}][<unknown>] Rejected due to protocol violation:",
                        channel,
                        scheme.isPresent() ? scheme.get().uriText() : "unknown",
                        hostname.isPresent() ? hostname.get() : "<unknown-host>",
                        path.isPresent() ? path.get() : "/<unknown-path>",
                        method.getName(),
                        result.cause());
        }
        return result;
    }

    private static StringBuilder contentSize(Object content) {
        StringBuilder builder = new StringBuilder(16);
        if (content instanceof ByteBuf) {
            ByteBuf byteBuf = (ByteBuf) content;
            UnitFormatter.appendSize(builder, byteBuf);
        } else {
            builder.append("unknown");
        }
        return builder;
    }

    @Override
    public <T> T decodeResponse(ServiceInvocationContext ctx, ByteBuf content, Object originalResponse)
            throws Exception {
        final Logger logger = ctx.logger();
        if (logger.isInfoEnabled() && ctx.hasAttr(START_TIME_NANOS)) {
            return logAndDecodeResponse(ctx, logger, originalResponse, content);
        } else {
            return delegate().decodeResponse(ctx, content, originalResponse);
        }
    }

    private <T> T logAndDecodeResponse(ServiceInvocationContext ctx, Logger logger, Object originalResponse,
                                       ByteBuf content) throws Exception {
        final long endTimeNanos = ticker.read();
        final long startTimeNanos = ctx.attr(START_TIME_NANOS).get();

        try {
            T result = delegate().decodeResponse(ctx, content, originalResponse);
            logger.info("Response: {} ({})", result, elapsed(startTimeNanos, endTimeNanos));
            return result;
        } catch (Throwable cause) {
            logger.info("Exception: {} ({})", cause, elapsed(startTimeNanos, endTimeNanos));
            throw cause;
        }
    }
}
