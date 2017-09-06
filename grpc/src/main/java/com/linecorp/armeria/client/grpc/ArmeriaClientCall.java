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

package com.linecorp.armeria.client.grpc;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.curioswitch.common.protobuf.json.MessageMarshaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.DefaultHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageDeframer;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageDeframer.ByteBufOrStream;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageFramer;
import com.linecorp.armeria.internal.grpc.GrpcHeaderNames;
import com.linecorp.armeria.internal.grpc.GrpcLogUtil;
import com.linecorp.armeria.internal.grpc.GrpcMessageMarshaller;
import com.linecorp.armeria.internal.grpc.HttpStreamReader;
import com.linecorp.armeria.internal.grpc.TimeoutHeaderUtil;
import com.linecorp.armeria.internal.grpc.TransportStatusListener;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.Codec.Identity;
import io.grpc.Compressor;
import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.netty.buffer.ByteBuf;

/**
 * Encapsulates the state of a single client call, writing messages from the client and reading responses
 * from the server, passing to business logic via {@link ClientCall.Listener}.
 */
class ArmeriaClientCall<I, O> extends ClientCall<I, O>
        implements ArmeriaMessageDeframer.Listener, TransportStatusListener {

    private static final Runnable NO_OP = () -> { };

    private static final Metadata EMPTY_METADATA = new Metadata();

    private static final Logger logger = LoggerFactory.getLogger(ArmeriaClientCall.class);

    private final ClientRequestContext ctx;
    private final Client<HttpRequest, HttpResponse> httpClient;
    private final DefaultHttpRequest req;
    private final CallOptions callOptions;
    private final ArmeriaMessageFramer messageFramer;
    private final GrpcMessageMarshaller<I, O> marshaller;
    private final CompressorRegistry compressorRegistry;
    private final DecompressorRegistry decompressorRegistry;
    private final HttpStreamReader responseReader;
    @Nullable
    private final Executor executor;

    // Effectively final, only set once during start()
    private Listener<O> listener;

    private boolean cancelCalled;

    ArmeriaClientCall(
            ClientRequestContext ctx,
            Client<HttpRequest, HttpResponse> httpClient,
            DefaultHttpRequest req,
            MethodDescriptor<I, O> method,
            int maxOutboundMessageSizeBytes,
            int maxInboundMessageSizeBytes,
            CallOptions callOptions,
            CompressorRegistry compressorRegistry,
            DecompressorRegistry decompressorRegistry,
            SerializationFormat serializationFormat,
            @Nullable MessageMarshaller jsonMarshaller) {
        this.ctx = ctx;
        this.httpClient = httpClient;
        this.req = req;
        this.callOptions = callOptions;
        this.compressorRegistry = compressorRegistry;
        this.decompressorRegistry = decompressorRegistry;
        this.messageFramer = new ArmeriaMessageFramer(ctx.alloc(), maxOutboundMessageSizeBytes);
        this.marshaller = new GrpcMessageMarshaller<>(
                ctx.alloc(), serializationFormat, method, jsonMarshaller);
        responseReader = new HttpStreamReader(
                decompressorRegistry,
                new ArmeriaMessageDeframer(this, maxInboundMessageSizeBytes, ctx.alloc()),
                this);
        executor = callOptions.getExecutor();
    }

    @Override
    public void start(Listener<O> responseListener, Metadata unused) {
        requireNonNull(responseListener, "responseListener");
        final Compressor compressor;
        if (callOptions.getCompressor() != null) {
            compressor = compressorRegistry.lookupCompressor(callOptions.getCompressor());
            if (compressor == null) {
                responseListener.onClose(
                        Status.INTERNAL.withDescription(
                                "Unable to find compressor by name " + callOptions.getCompressor()),
                        EMPTY_METADATA);
                return;
            }
        } else {
            compressor = Identity.NONE;
        }
        messageFramer.setCompressor(compressor);
        prepareHeaders(req.headers(), compressor);
        listener = responseListener;
        final HttpResponse res;
        try {
            res = httpClient.execute(ctx, req);
        } catch (Exception e) {
            try (SafeCloseable ignored = RequestContext.push(ctx)) {
                listener.onClose(Status.fromThrowable(e), EMPTY_METADATA);
            }
            return;
        }
        res.subscribe(responseReader);
    }

    @Override
    public void request(int numMessages) {
        if (ctx.eventLoop().inEventLoop()) {
            responseReader.request(numMessages);
        } else {
            ctx.eventLoop().submit(() -> responseReader.request(numMessages));
        }
    }

    @Override
    public void cancel(@Nullable String message, @Nullable Throwable cause) {
        if (message == null && cause == null) {
            cause = new CancellationException("Cancelled without a message or cause");
            logger.warn("Cancelling without a message or cause is suboptimal", cause);
        }
        if (cancelCalled) {
            return;
        }
        cancelCalled = true;
        Status status = Status.CANCELLED;
        if (message != null) {
            status = status.withDescription(message);
        }
        if (cause != null) {
            status = status.withCause(cause);
        }
        responseReader.cancel();
        req.close(status.asException());
        if (listener != null) {
            try (SafeCloseable ignored = RequestContext.push(ctx)) {
                listener.onClose(status, EMPTY_METADATA);
            }
            notifyExecutor();
        }
    }

    @Override
    public void halfClose() {
        req.close();
    }

    @Override
    public void sendMessage(I message) {
        try {
            ByteBuf serialized = marshaller.serializeRequest(message);
            boolean success = false;
            final HttpData frame;
            try {
                frame = messageFramer.writePayload(serialized);
                success = true;
            } finally {
                if (!success) {
                    serialized.release();
                }
            }
            req.write(frame);
        } catch (Throwable t) {
            cancel(null, t);
        }
    }

    @Override
    public void setMessageCompression(boolean enabled) {
        checkState(req != null, "Not started");
        messageFramer.setMessageCompression(enabled);
    }

    @Override
    public void messageRead(ByteBufOrStream message) {
        try {
            O msg = marshaller.deserializeResponse(message);
            try (SafeCloseable ignored = RequestContext.push(ctx)) {
                listener.onMessage(msg);
            }
        } catch (Throwable t) {
            req.close(Status.fromThrowable(t).asException());
            throw (t instanceof RuntimeException) ? (RuntimeException) t : new RuntimeException(t);
        }
    }

    @Override
    public void endOfStream() {
        // Ignore - the client call is terminated by headers, not data.
    }

    @Override
    public void transportReportStatus(Status status) {
        responseReader.cancel();
        try (SafeCloseable ignored = RequestContext.push(ctx)) {
            listener.onClose(status, EMPTY_METADATA);
        }
        ctx.logBuilder().responseContent(GrpcLogUtil.rpcResponse(status), null);
        notifyExecutor();
    }

    private void prepareHeaders(HttpHeaders headers, Compressor compressor) {
        if (compressor != Identity.NONE) {
            headers.set(GrpcHeaderNames.GRPC_ENCODING, compressor.getMessageEncoding());
        }
        String advertisedEncodings = String.join(",", decompressorRegistry.getAdvertisedMessageEncodings());
        if (!advertisedEncodings.isEmpty()) {
            headers.add(GrpcHeaderNames.GRPC_ACCEPT_ENCODING, advertisedEncodings);
        }
        headers.add(GrpcHeaderNames.GRPC_TIMEOUT,
                    TimeoutHeaderUtil.toHeaderValue(
                            TimeUnit.MILLISECONDS.toNanos(ctx.responseTimeoutMillis())));
    }

    /**
     * Armeria does not support {@link CallOptions} set by the user, however gRPC stubs set an {@link Executor}
     * within blocking stubs which is used to notify the stub when processing is finished. It's unclear why
     * the stubs use a loop and {@link java.util.concurrent.Future#isDone()} instead of just blocking on
     * {@link java.util.concurrent.Future#get}, but we make sure to run the {@link Executor} so the stub can
     * be notified of completion.
     */
    private void notifyExecutor() {
        if (executor != null) {
            executor.execute(NO_OP);
        }
    }
}
