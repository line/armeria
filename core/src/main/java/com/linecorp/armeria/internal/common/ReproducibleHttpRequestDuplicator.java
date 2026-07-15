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

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
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
 * <p>The lifecycle matches {@link com.linecorp.armeria.common.stream.StreamMessageDuplicator}: every
 * request produced by {@link #duplicate(RequestHeaders)} stays active until it completes on its own,
 * for as long as this duplicator is not aborted. {@link #close()} only prevents further duplication;
 * it leaves outstanding requests streaming. {@link #abort(Throwable)} closes the duplicator and aborts
 * every outstanding request so their bodies (e.g. open files) are released. This also lets multiple
 * produced requests be outstanding concurrently (e.g. for hedging), not just the most recent one.
 *
 * <p>All state transitions are guarded by the instance lock so that {@link #abort(Throwable)}, which
 * may be invoked from any thread (e.g. a response timeout on the event loop while the caller thread is
 * mid-{@link #duplicate(RequestHeaders)}), cannot miss a request that is being produced concurrently
 * and leak it. A request produced after the duplicator is closed or aborted is aborted immediately by
 * {@code duplicate}, and {@code duplicate} then throws instead of returning a request that would never
 * be torn down.
 */
public final class ReproducibleHttpRequestDuplicator implements HttpRequestDuplicator {

    private final RequestHeaders headers;
    private final Supplier<? extends StreamMessage<? extends HttpObject>> bodyFactory;

    private final Set<HttpRequest> children =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private boolean closed;
    @Nullable
    private Throwable abortCause;

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

        // Produce the body outside the lock: the factory is user code (it may open a file or block),
        // and holding the lock across it would widen the window during which a concurrent abort() is
        // stalled. Fail fast on a broken factory by propagating to the caller (RetryingClient /
        // RedirectingClient), which completes the response exceptionally instead of re-judging an
        // aborted request and wasting the retry budget.
        final StreamMessage<? extends HttpObject> body =
                requireNonNull(bodyFactory.get(), "bodyFactory.get() returned null.");
        final HttpRequest produced = HttpRequest.of(newHeaders, body);

        final Throwable abortCause;
        synchronized (this) {
            if (!closed) {
                children.add(produced);
                // Drop the request from the tracked set once it finishes so the set does not grow
                // unbounded across a long retry/redirect chain. Registered while holding the lock so a
                // concurrent abort() sees a consistent snapshot.
                produced.whenComplete().handle((unused, cause) -> {
                    synchronized (this) {
                        children.remove(produced);
                    }
                    return null;
                });
                return produced;
            }
            // Closed or aborted while we were producing: tear down the just-produced request so its
            // body is released, then report the closed state to the caller.
            abortCause = this.abortCause;
        }
        abort(produced, abortCause);
        throw new IllegalStateException("duplicator is closed or aborted.");
    }

    @Override
    public void close() {
        // Prevent further duplication but leave outstanding requests streaming: on the success path the
        // wire owns them and is still streaming, and the StreamMessageDuplicator contract says close()
        // must not abort issued duplicates.
        synchronized (this) {
            closed = true;
        }
    }

    @Override
    public void abort() {
        abortAll(null);
    }

    @Override
    public void abort(Throwable cause) {
        abortAll(requireNonNull(cause, "cause"));
    }

    private void abortAll(@Nullable Throwable cause) {
        final List<HttpRequest> toAbort;
        synchronized (this) {
            closed = true;
            if (cause != null) {
                // Remember the cause so a request produced by a racing duplicate() is aborted with it.
                abortCause = cause;
            }
            toAbort = new ArrayList<>(children);
            children.clear();
        }
        // Abort outside the lock: whenComplete callbacks fire synchronously during abort() and re-enter
        // the lock to remove the child, so holding it here would extend the critical section across every
        // child's teardown and contend needlessly. The children set was already cleared above.
        for (HttpRequest child : toAbort) {
            abort(child, cause);
        }
    }

    private static void abort(HttpRequest request, @Nullable Throwable cause) {
        // Aborting an already-subscribed stream is a no-op; otherwise this releases a
        // produced-but-unsubscribed body (e.g. an open file).
        if (cause != null) {
            request.abort(cause);
        } else {
            request.abort();
        }
    }
}
