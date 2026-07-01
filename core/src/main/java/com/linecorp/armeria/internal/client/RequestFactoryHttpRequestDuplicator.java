/*
 * Copyright 2026 LINE Corporation
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

package com.linecorp.armeria.internal.client;

import static java.util.Objects.requireNonNull;

import java.util.function.Supplier;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestDuplicator;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * An {@link HttpRequestDuplicator} that reproduces the request body without buffering it.
 *
 * <p>The first {@link #duplicate()} returns the original request passed to the client; every
 * subsequent call obtains a fresh request from the supplied factory. This avoids the ~2 GiB
 * {@code int} size limit and the memory cost of {@code DefaultStreamMessageDuplicator}, which
 * buffers the whole body for replay.
 */
public final class RequestFactoryHttpRequestDuplicator implements HttpRequestDuplicator {

    private final HttpRequest originalReq;
    private final Supplier<HttpRequest> factory;

    // RetryingClient and RedirectingClient drive the duplicator sequentially (never concurrently):
    // the first duplicate() may run on the caller thread while later attempts run on the request's
    // event loop, but each access happens-after the previous one via the async response future.
    // Marked volatile for safe publication across that thread hand-off, since accesses are ordered
    // but not confined to a single thread.
    private volatile boolean firstDuplicateIssued;

    public RequestFactoryHttpRequestDuplicator(HttpRequest originalReq,
                                               Supplier<HttpRequest> factory) {
        this.originalReq = requireNonNull(originalReq, "originalReq");
        this.factory = requireNonNull(factory, "factory");
    }

    @Override
    public RequestHeaders headers() {
        return originalReq.headers();
    }

    @Override
    public HttpRequest duplicate() {
        return duplicate0(null);
    }

    @Override
    public HttpRequest duplicate(RequestHeaders newHeaders) {
        requireNonNull(newHeaders, "newHeaders");
        return duplicate0(newHeaders);
    }

    private HttpRequest duplicate0(@Nullable RequestHeaders newHeaders) {
        if (!firstDuplicateIssued) {
            firstDuplicateIssued = true;
            return withHeaders(originalReq, newHeaders);
        }

        final HttpRequest next;
        try {
            next = factory.get();
        } catch (Throwable t) {
            return failed(newHeaders != null ? newHeaders : headers(), t);
        }
        if (next == null) {
            return failed(newHeaders != null ? newHeaders : headers(), new NullPointerException(
                    "The request body factory returned null."));
        }
        return withHeaders(next, newHeaders);
    }

    private static HttpRequest withHeaders(HttpRequest req, @Nullable RequestHeaders newHeaders) {
        return newHeaders != null ? req.withHeaders(newHeaders) : req;
    }

    private static HttpRequest failed(RequestHeaders headers, Throwable cause) {
        // Return a request that fails when subscribed. It is aborted eagerly so that
        // whenComplete() is completed exceptionally even before any subscription, which
        // lets callers observe the failure without subscribing to the body.
        final HttpRequestWriter failed = HttpRequest.streaming(headers);
        failed.abort(cause);
        return failed;
    }

    @Override
    public void close() {
        abortOriginalIfUnused(null);
    }

    @Override
    public void abort() {
        abortOriginalIfUnused(null);
    }

    @Override
    public void abort(@Nullable Throwable cause) {
        abortOriginalIfUnused(cause);
    }

    private void abortOriginalIfUnused(@Nullable Throwable cause) {
        // If the original request has never been handed to the wire, abort it to avoid a leak.
        // Once the wire owns the subscription, abort() on an already-subscribed stream is a no-op.
        if (!firstDuplicateIssued) {
            firstDuplicateIssued = true;
            if (cause != null) {
                originalReq.abort(cause);
            } else {
                originalReq.abort();
            }
        }
    }
}
