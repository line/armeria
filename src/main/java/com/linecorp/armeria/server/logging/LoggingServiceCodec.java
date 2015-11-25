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

package com.linecorp.armeria.server.logging;

import static com.linecorp.armeria.common.util.UnitFormatter.elapsedAndSize;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.DecoratingServiceCodec;
import com.linecorp.armeria.server.ServiceCodec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Promise;

final class LoggingServiceCodec extends DecoratingServiceCodec {

    private static final Logger logger = LoggerFactory.getLogger(LoggingServiceCodec.class);

    private static final AttributeKey<Long> START_TIME_NANOS =
            AttributeKey.valueOf(LoggingServiceCodec.class, "START_TIME_NANOS");

    LoggingServiceCodec(ServiceCodec codec) {
        super(codec);
    }

    @Override
    public DecodeResult decodeRequest(
            Channel ch, SessionProtocol sessionProtocol, String hostname, String path, String mappedPath,
            ByteBuf in, Object originalRequest, Promise<Object> promise) throws Exception {

        final int requestSize = in.readableBytes();
        final DecodeResult result =
                delegate().decodeRequest(ch, sessionProtocol, hostname, path, mappedPath, in, originalRequest,
                                         promise);


        switch (result.type()) {
        case SUCCESS: {
            // Successful decode
            final ServiceInvocationContext ctx = result.invocationContext();
            final Logger logger = ctx.logger();
            if (logger.isInfoEnabled()) {
                logger.info("Request: {} ({}B)", ctx.params(), requestSize);
                ctx.attr(START_TIME_NANOS).set(System.nanoTime());
            }
            break;
        }
        case FAILURE:
            // Failed decode
            final SerializationFormat serFmt = result.decodedSerializationFormat();

            logger.warn("{}[{}+{}://{}{}#{}][{}] Rejected due to protocol violation:",
                        ch,
                        serFmt.uriText(),
                        sessionProtocol.uriText(),
                        hostname,
                        path,
                        result.decodedMethod().orElse("<unknown>"),
                        result.decodedInvocationId().orElse("<unknown>"),
                        result.cause());
            break;
        }

        return result;
    }

    @Override
    public ByteBuf encodeResponse(ServiceInvocationContext ctx, Object response) throws Exception {
        final Logger logger = ctx.logger();
        if (logger.isInfoEnabled() && ctx.hasAttr(START_TIME_NANOS)) {
            return logAndEncodeResponse(ctx, logger, response);
        } else {
            return delegate().encodeResponse(ctx, response);
        }
    }

    private ByteBuf logAndEncodeResponse(
            ServiceInvocationContext ctx, Logger logger, Object response) throws Exception {

        final long endTimeNanos = System.nanoTime();
        final long startTimeNanos = ctx.attr(START_TIME_NANOS).get();
        final ByteBuf encoded = delegate().encodeResponse(ctx, response);

        logger.info("Response: {} ({})", response, elapsedAndSize(startTimeNanos, endTimeNanos, encoded));

        return encoded;
    }

    @Override
    public ByteBuf encodeFailureResponse(ServiceInvocationContext ctx, Throwable cause) throws Exception {
        final Logger logger = ctx.logger();
        if (logger.isWarnEnabled() && ctx.hasAttr(START_TIME_NANOS)) {
            return logAndEncodeFailedResponse(ctx, logger, cause);
        } else {
            return delegate().encodeFailureResponse(ctx, cause);
        }
    }

    private ByteBuf logAndEncodeFailedResponse(
            ServiceInvocationContext ctx, Logger logger, Throwable cause) throws Exception {

        final long endTimeNanos = System.nanoTime();
        final long startTimeNanos = ctx.attr(START_TIME_NANOS).get();
        final ByteBuf encoded = delegate().encodeFailureResponse(ctx, cause);

        logger.warn("Exception: {} ({})", cause, elapsedAndSize(startTimeNanos, endTimeNanos, encoded), cause);

        return encoded;
    }

}
