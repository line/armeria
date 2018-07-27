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

package com.linecorp.armeria.internal.grpc;

import static java.util.Objects.requireNonNull;

import java.util.function.BiConsumer;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;

import io.grpc.Decompressor;
import io.grpc.DecompressorRegistry;
import io.grpc.Status;

/**
 * A {@link Subscriber} to read HTTP messages and pass to gRPC business logic.
 */
public class HttpStreamReader implements Subscriber<HttpObject>, BiConsumer<Void, Throwable> {

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
                final HttpStatus status = headers.status();
                if (status == null) {
                    // Not allowed to have empty leading headers, kill the stream hard.
                    transportStatusListener.transportReportStatus(
                            Status.INTERNAL.withDescription("Missing HTTP status code"));
                    return;
                }

                if (status.codeClass() == HttpStatusClass.INFORMATIONAL) {
                    // Skip informational headers.
                    return;
                }

                sawLeadingHeaders = true;

                if (!status.equals(HttpStatus.OK)) {
                    transportStatusListener.transportReportStatus(
                            GrpcStatus.httpStatusToGrpcStatus(status.code()));
                    return;
                }
            }

            final String grpcStatus = headers.get(GrpcHeaderNames.GRPC_STATUS);
            if (grpcStatus != null) {
                Status status = Status.fromCodeValue(Integer.valueOf(grpcStatus));
                if (status.getCode() == Status.OK.getCode()) {
                   // Successful response, finish delivering messages before returning the status.
                   closeDeframer();
                }
                final String grpcMessage = headers.get(GrpcHeaderNames.GRPC_MESSAGE);
                if (grpcMessage != null) {
                    status = status.withDescription(grpcMessage);
                }
                transportStatusListener.transportReportStatus(status);
                return;
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
                deframer.decompressor(decompressor);
            }
            requestHttpFrame();
            return;
        }
        final HttpData data = (HttpData) obj;
        try {
            deframer.deframe(data, false);
        } catch (Throwable cause) {
            try {
                transportStatusListener.transportReportStatus(Status.fromThrowable(cause));
                return;
            } finally {
                deframer.close();
            }
        }
        requestHttpFrame();
    }

    @Override
    public void onError(Throwable cause) {
        // Handled by accept() below.
    }

    @Override
    public void onComplete() {
        // Handled by accept() below.
    }

    @Override
    public void accept(Void unused, Throwable cause) {
        if (cancelled) {
            return;
        }

        if (cause == null) {
            closeDeframer();
        } else {
            transportStatusListener.transportReportStatus(GrpcStatus.fromThrowable(cause));
        }
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

    private void closeDeframer() {
        if (!deframer.isClosed()) {
            deframer.deframe(HttpData.EMPTY_DATA, true);
            deframer.close();
        }
    }

    private void requestHttpFrame() {
        assert subscription != null;
        if (deframer.isStalled()) {
            subscription.request(1);
        }
    }
}
