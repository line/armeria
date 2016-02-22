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

package com.linecorp.armeria.server.metrics;

import java.util.Optional;
import java.util.function.LongSupplier;

import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metrics.MetricConsumer;
import com.linecorp.armeria.server.DecoratingServiceCodec;
import com.linecorp.armeria.server.RequestTimeoutException;
import com.linecorp.armeria.server.ServiceCodec;
import com.linecorp.armeria.server.ServiceConfig;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Promise;

/**
 * Decorator to collect service metrics.
 *
 * This class is expected to be used with other {@link MetricConsumer}
 */
final class MetricCollectingServiceCodec extends DecoratingServiceCodec {
    private static final AttributeKey<MetricsData> METRICS =
            AttributeKey.valueOf(MetricCollectingServiceCodec.class, "METRICS");

    private final MetricConsumer metricConsumer;

    /**
     * Creates a new instance that decorates the specified {@link ServiceCodec} with
     * the specified {@link MetricConsumer}.
     */
    MetricCollectingServiceCodec(ServiceCodec codec, MetricConsumer consumer) {
        super(codec);
        metricConsumer = consumer;
    }

    @Override
    public DecodeResult decodeRequest(ServiceConfig cfg, Channel ch, SessionProtocol sessionProtocol,
                                      String hostname, String path, String mappedPath, ByteBuf in,
                                      Object originalRequest, Promise<Object> promise) throws Exception {

        final long startTime = System.nanoTime();
        final int requestSize = in.readableBytes();

        DecodeResult decodeResult = delegate().decodeRequest(
                cfg, ch, sessionProtocol, hostname, path, mappedPath, in, originalRequest, promise);

        LongSupplier lazyElapsedTime = () -> System.nanoTime() - startTime;

        switch (decodeResult.type()) {
        case SUCCESS: {
            ServiceInvocationContext context = decodeResult.invocationContext();
            context.attr(METRICS).set(new MetricsData(requestSize, startTime));

            promise.addListener(future -> {
                if (!future.isSuccess()) {
                    // encodeFailureResponse will process this case.
                    return;
                }
                Object result = future.getNow();

                if (result instanceof FullHttpResponse) {
                    FullHttpResponse httpResponse = (FullHttpResponse) result;
                    metricConsumer.invocationComplete(
                            context.scheme(), httpResponse.status().code(),
                            lazyElapsedTime.getAsLong(), requestSize, httpResponse.content().readableBytes(),
                            hostname, path, decodeResult.decodedMethod());
                }
                // encodeResponse will process this case.
            });
            break;
        }
        case FAILURE: {
            final Object errorResponse = decodeResult.errorResponse();
            if (errorResponse instanceof FullHttpResponse) {
                FullHttpResponse httpResponse = (FullHttpResponse) errorResponse;
                metricConsumer.invocationComplete(
                        Scheme.of(decodeResult.decodedSerializationFormat(), sessionProtocol),
                        httpResponse.status().code(), lazyElapsedTime.getAsLong(), requestSize,
                        httpResponse.content().readableBytes(), hostname, path, decodeResult.decodedMethod());
            } else {
                metricConsumer.invocationComplete(
                        Scheme.of(decodeResult.decodedSerializationFormat(), sessionProtocol),
                        HttpResponseStatus.BAD_REQUEST.code(), lazyElapsedTime.getAsLong(), requestSize, 0, hostname,
                        path, decodeResult.decodedMethod());
            }
            break;
        }
        case NOT_FOUND:
            metricConsumer.invocationComplete(
                    Scheme.of(decodeResult.decodedSerializationFormat(), sessionProtocol),
                    HttpResponseStatus.NOT_FOUND.code(), lazyElapsedTime.getAsLong(), requestSize, 0, hostname, path,
                    decodeResult.decodedMethod());
            break;
        }

        return decodeResult;
    }

    private void invokeComplete(ServiceInvocationContext ctx, HttpResponseStatus status, ByteBuf buf)
            throws Exception {
        MetricsData metricsData = ctx.attr(METRICS).get();
        long elapsedTime = System.nanoTime() - metricsData.startTimeNanos;
        metricConsumer.invocationComplete(
                ctx.scheme(), status.code(), elapsedTime, metricsData.requestSizeBytes,
                buf.readableBytes(), ctx.host(), ctx.path(), Optional.of(ctx.method()));
    }

    @Override
    public ByteBuf encodeFailureResponse(ServiceInvocationContext ctx, Throwable cause) throws Exception {
        ByteBuf buf = delegate().encodeFailureResponse(ctx, cause);
        if (cause instanceof RequestTimeoutException) {
            invokeComplete(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, buf);
        } else {
            invokeComplete(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, buf);
        }
        return buf;
    }

    @Override
    public ByteBuf encodeResponse(ServiceInvocationContext ctx, Object response) throws Exception {
        ByteBuf buf = delegate().encodeResponse(ctx, response);
        invokeComplete(ctx, HttpResponseStatus.OK, buf);
        return buf;
    }

    /**
     * internal container for metric data
     */
    private static class MetricsData {
        private final int requestSizeBytes;
        private final long startTimeNanos;

        private MetricsData(int requestSizeBytes, long startTimeNanos) {
            this.requestSizeBytes = requestSizeBytes;
            this.startTimeNanos = startTimeNanos;
        }
    }
}
