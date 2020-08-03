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

package com.linecorp.armeria.internal.common.grpc;

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframer;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;

import io.grpc.Decompressor;
import io.grpc.DecompressorRegistry;
import io.grpc.Status;

/**
 * A {@link Subscriber} to read HTTP messages and pass to gRPC business logic.
 */
public final class HttpStreamReader implements Subscriber<HttpObject> {

    private final DecompressorRegistry decompressorRegistry;
    private final TransportStatusListener transportStatusListener;

    @VisibleForTesting
    public final ArmeriaMessageDeframer deframer;

    @Nullable
    private Subscription subscription;

    private int deferredInitialMessageRequest;

    private volatile boolean cancelled;

    private boolean sawLeadingHeaders;

    public HttpStreamReader(DecompressorRegistry decompressorRegistry,
                            ArmeriaMessageDeframer deframer,
                            TransportStatusListener transportStatusListener) {
        this.decompressorRegistry = requireNonNull(decompressorRegistry, "decompressorRegistry");
        this.deframer = requireNonNull(deframer, "deframer");
        this.transportStatusListener = requireNonNull(transportStatusListener, "transportStatusListener");
    }

    // Must be called from the IO thread.
    public void request(int numMessages) {
        if (subscription == null) {
            deferredInitialMessageRequest += numMessages;
            return;
        }
        deframer.request(numMessages);
        requestHttpFrame();
    }

    // Must be called from the IO thread.
    @Override
    public void onSubscribe(Subscription subscription) {
        this.subscription = subscription;
        if (cancelled) {
            subscription.cancel();
            return;
        }
        if (deferredInitialMessageRequest > 0) {
            request(deferredInitialMessageRequest);
        }
    }

    @Override
    public void onNext(HttpObject obj) {
        if (cancelled) {
            return;
        }
        if (obj instanceof HttpHeaders) {
            // Only clients will see headers from a stream. It doesn't hurt to share this logic between server
            // and client though as everything else is identical.
            final HttpHeaders headers = (HttpHeaders) obj;

            if (!sawLeadingHeaders) {
                final String statusText = headers.get(HttpHeaderNames.STATUS);
                if (statusText == null) {
                    // Not allowed to have empty leading headers, kill the stream hard.
                    transportStatusListener.transportReportStatus(
                            Status.INTERNAL.withDescription("Missing HTTP status code"));
                    return;
                }

                if (ArmeriaHttpUtil.isInformational(statusText)) {
                    // Skip informational headers.
                    return;
                }

                sawLeadingHeaders = true;

                final HttpStatus status = HttpStatus.valueOf(statusText);
                if (!status.equals(HttpStatus.OK)) {
                    transportStatusListener.transportReportStatus(
                            GrpcStatus.httpStatusToGrpcStatus(status.code()));
                    return;
                }
            }

            final String grpcStatus = headers.get(GrpcHeaderNames.GRPC_STATUS);
            if (grpcStatus != null) {
                if (deframer.isClosed()) {
                    GrpcStatus.reportStatus(headers, this, transportStatusListener);
                } else {
                    // A gRPC client could not receive messages fully yet.
                    // Let ArmeriaClientCall be closed when the gRPC client has been consumed all messages.
                    deframer.whenClosed().thenRun(() -> {
                        GrpcStatus.reportStatus(headers, this, transportStatusListener);
                    });
                    deframer.closeWhenComplete();
                }
            }

            // Headers without grpc-status are the leading headers of a non-failing response, prepare to receive
            // messages.
            final String grpcEncoding = headers.get(GrpcHeaderNames.GRPC_ENCODING);
            if (grpcEncoding != null) {
                final Decompressor decompressor = decompressorRegistry.lookupDecompressor(grpcEncoding);
                if (decompressor == null) {
                    transportStatusListener.transportReportStatus(Status.INTERNAL.withDescription(
                            "Can't find decompressor for " + grpcEncoding));
                    return;
                }
                try {
                    deframer.decompressor(ForwardingDecompressor.forGrpc(decompressor));
                } catch (Throwable t) {
                    transportStatusListener.transportReportStatus(GrpcStatus.fromThrowable(t));
                    return;
                }
            }
            requestHttpFrame();
            return;
        }
        final HttpData data = (HttpData) obj;
        try {
            deframer.deframe(data, false);
        } catch (Throwable cause) {
            try {
                transportStatusListener.transportReportStatus(GrpcStatus.fromThrowable(cause));
                return;
            } finally {
                deframer.close();
            }
        }
        requestHttpFrame();
    }

    @Override
    public void onError(Throwable cause) {
        if (cancelled) {
            return;
        }
        transportStatusListener.transportReportStatus(GrpcStatus.fromThrowable(cause));
    }

    @Override
    public void onComplete() {
        if (cancelled) {
            return;
        }
        closeDeframer();
    }

    public void cancel() {
        cancelled = true;
        if (subscription != null) {
            subscription.cancel();
        }
        if (!deframer.isClosed()) {
            deframer.close();
        }
    }

    void closeDeframer() {
        // closeDeframer() could be called when deframer.isClosing() due to a race condition like the following:
        //
        // 1) HttpStreamReader received all data from publisher and added them to unprocessed of deframer.
        // 2) A gRPC client does not request next messages yet, so deframer still has unprocessedBytes and
        //    is not stalled.
        // 3) HttpStreamReader receives onCompleted signal and closes deframer.
        // 4) A gRPC client requests a message and the received message contains trailers,
        //    so ArmeriaClientCall tries to close deframer.
        if (!deframer.isClosing() && !deframer.isClosed()) {
            deframer.deframe(HttpData.empty(), true);
            deframer.closeWhenComplete();
        }
    }

    private void requestHttpFrame() {
        assert subscription != null;
        if (deframer.isStalled()) {
            subscription.request(1);
        }
    }
}
