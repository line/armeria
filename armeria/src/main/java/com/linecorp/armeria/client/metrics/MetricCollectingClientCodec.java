package com.linecorp.armeria.client.metrics;

import java.lang.reflect.Method;
import java.util.Optional;

import com.linecorp.armeria.client.ClientCodec;
import com.linecorp.armeria.client.DecoratingClientCodec;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metrics.MetricConsumer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.AttributeKey;

/**
 * Decorator to collect client metrics.
 * <p>
 * This class is expected to be used with other {@link MetricConsumer}
 */
class MetricCollectingClientCodec extends DecoratingClientCodec {

    private static final AttributeKey<MetricsData> METRICS =
            AttributeKey.valueOf(MetricCollectingClientCodec.class, "METRICS");

    private final MetricConsumer metricConsumer;

    /**
     * Creates a new instance that decorates the specified {@link ClientCodec}.
     */
    MetricCollectingClientCodec(ClientCodec delegate, MetricConsumer metricConsumer) {
        super(delegate);
        this.metricConsumer = metricConsumer;
    }

    @Override
    public EncodeResult encodeRequest(Channel channel, SessionProtocol sessionProtocol, Method method,
                                      Object[] args) {
        long startTime = System.nanoTime();
        EncodeResult result = delegate().encodeRequest(channel, sessionProtocol, method, args);
        if (result.isSuccess()) {
            ServiceInvocationContext context = result.invocationContext();
            context.attr(METRICS).set(new MetricsData(getRequestSize(result.content()), startTime));
            metricConsumer.invocationStarted(
                    context.scheme(), context.host(), context.path(), Optional.of(context.method()));
        } else {
            metricConsumer.invocationComplete(
                    result.encodedScheme().orElse(Scheme.of(SerializationFormat.UNKNOWN, sessionProtocol)),
                    HttpResponseStatus.BAD_REQUEST.code(),
                    System.nanoTime() - startTime, 0, 0,
                    result.encodedHost().orElse("__unknown_host__"),
                    result.encodedPath().orElse("__unknown_path__"),
                    Optional.empty(),
                    false);
        }
        return result;
    }

    private void invokeComplete(
            ServiceInvocationContext ctx, HttpResponseStatus status, int responseSizeBytes) {
        MetricsData metricsData = ctx.attr(METRICS).get();
        metricConsumer.invocationComplete(
                ctx.scheme(), status.code(), System.nanoTime() - metricsData.startTimeNanos,
                metricsData.requestSizeBytes, responseSizeBytes, ctx.host(), ctx.path(),
                Optional.of(ctx.method()), true);
    }

    @Override
    public <T> T decodeResponse(ServiceInvocationContext ctx, ByteBuf content, Object originalResponse)
            throws Exception {
        int responseSizeBytes = content.readableBytes();
        try {
            T response = delegate().decodeResponse(ctx, content, originalResponse);
            invokeComplete(ctx, getResponseStatus(response), responseSizeBytes);
            return response;
        } catch (Throwable t) {
            invokeComplete(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, responseSizeBytes);
            throw t;
        }
    }

    private static HttpResponseStatus getResponseStatus(Object response) {
        if (response instanceof HttpResponse) {
            return ((HttpResponse) response).status();
        }
        return HttpResponseStatus.OK;
    }

    private static int getRequestSize(Object content) {
        if (content instanceof ByteBuf) {
            return ((ByteBuf) content).readableBytes();
        } else if (content instanceof ByteBufHolder) {
            return ((ByteBufHolder) content).content().readableBytes();
        }
        return 0;
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
