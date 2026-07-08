/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.internal.common;

import static java.util.Objects.requireNonNull;

import java.util.function.Supplier;

import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestDuplicator;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.StreamMessage;

/**
 * An {@link HttpRequestDuplicator} that reproduces the request body without buffering it. Every
 * {@link #duplicate()} — including the first — obtains a fresh body from the supplied factory, so no
 * attempt reuses another attempt's stream and the original request handed to the client is never put
 * on the wire. This avoids the ~2 GiB {@code int} size limit and the memory cost of
 * {@code DefaultStreamMessageDuplicator}, which buffers the whole body for replay.
 *
 * <p>{@code RetryingClient} and {@code RedirectingClient} drive this duplicator strictly
 * sequentially: at most one produced request is outstanding at a time, and each access
 * happens-after the previous one via the async response future. The duplicator tracks the single
 * last-produced request so that {@link #abort(Throwable)} can tear it down if it was produced but
 * never subscribed (e.g. endpoint selection threw before the wire took ownership). The fields are
 * {@code volatile} for safe publication across the caller-thread → event-loop hand-off, matching how
 * the clients drive it.
 */
public final class ReproducibleHttpRequestDuplicator implements HttpRequestDuplicator {

    private final RequestHeaders headers;
    private final Supplier<? extends StreamMessage<? extends HttpObject>> bodyFactory;

    @Nullable
    private volatile HttpRequest lastProduced;
    private volatile boolean closed;

    public ReproducibleHttpRequestDuplicator(
            RequestHeaders headers,
            Supplier<? extends StreamMessage<? extends HttpObject>> bodyFactory) {
        this.headers = requireNonNull(headers, "headers");
        this.bodyFactory = requireNonNull(bodyFactory, "bodyFactory");
    }

    @Override
    public RequestHeaders headers() {
        return headers;
    }

    @Override
    public HttpRequest duplicate() {
        return duplicate(headers);
    }

    @Override
    public HttpRequest duplicate(RequestHeaders newHeaders) {
        requireNonNull(newHeaders, "newHeaders");
        if (closed) {
            throw new IllegalStateException("duplicator is closed or aborted.");
        }

        // A produced-but-unsubscribed request from a previous attempt (e.g. endpoint selection threw
        // before the wire subscribed) would otherwise be lost when we overwrite lastProduced; abort it
        // so its body is released. Aborting an already-subscribed stream is a no-op.
        final HttpRequest previous = lastProduced;
        if (previous != null) {
            previous.abort();
        }

        // Fail fast on a broken factory: propagate to the caller (RetryingClient / RedirectingClient),
        // which completes the response exceptionally instead of re-judging an aborted request and
        // wasting the retry budget.
        final StreamMessage<? extends HttpObject> body =
                requireNonNull(bodyFactory.get(), "bodyFactory.get() returned null.");
        final HttpRequest produced = HttpRequest.of(newHeaders, body);
        lastProduced = produced;
        return produced;
    }

    @Override
    public void close() {
        // Let the last-produced request finish: on the success path the wire owns it and is still
        // streaming, and the StreamMessageDuplicator contract says close() must not abort issued
        // duplicates. If it was never subscribed, it is completed/aborted by its own consumer or by a
        // subsequent abort(); we intentionally do not abort it here.
        closed = true;
    }

    @Override
    public void abort() {
        abortLastProduced(null);
    }

    @Override
    public void abort(Throwable cause) {
        abortLastProduced(requireNonNull(cause, "cause"));
    }

    private void abortLastProduced(@Nullable Throwable cause) {
        closed = true;
        final HttpRequest lastProduced = this.lastProduced;
        if (lastProduced == null) {
            return;
        }
        this.lastProduced = null;
        // If the wire already owns the subscription, abort() on an already-subscribed stream is a
        // no-op. Otherwise this releases the produced-but-unsubscribed body (e.g. an open file).
        if (cause != null) {
            lastProduced.abort(cause);
        } else {
            lastProduced.abort();
        }
    }
}
