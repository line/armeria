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

package com.linecorp.armeria.server;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.channel.ChannelHandlerContext;

/**
 * Handles the {@code 100 Continue} response for a request with an {@code Expect: 100-continue} header and
 * content. The response is deferred until the request content is subscribed to, so that a decorator or
 * a service can reject the request before the client sends the content.
 *
 * <p>The {@code 100 Continue} response is written at most once, when either of the following happens first:
 * <ul>
 *   <li>the request content is subscribed to for the first time, or</li>
 *   <li>final response headers that allow the client to send the content are written.</li>
 * </ul>
 * It is suppressed if other final response headers are written first or the request is aborted before
 * the content is fully received, and it is not written by this handler if a service writes
 * {@code 100 Continue} by itself.
 *
 * <p>All methods except {@link #onSubscribe()} must be called from the event loop.
 */
final class ExpectContinueHandler {

    private static final ResponseHeaders CONTINUE_RESPONSE = ResponseHeaders.of(HttpStatus.CONTINUE);

    // Shared by all connections. Never modified because 'pending' is always 'false'.
    private static final ExpectContinueHandler UNSENDABLE = new ExpectContinueHandler(false);
    private static final ExpectContinueHandler UNSENDABLE_MULTIPLEX = new ExpectContinueHandler(true);

    /**
     * Returns an {@link ExpectContinueHandler} for a request with an {@code Expect: 100-continue} header
     * and content.
     *
     * @param multiplex whether the connection is multiplexed. If {@code false}, the connection has to be
     *                  closed after the response when {@code 100 Continue} is suppressed.
     */
    static ExpectContinueHandler of(RoutingContext routingCtx, ChannelHandlerContext ctx,
                                    ServerHttpObjectEncoder encoder, boolean multiplex) {
        if (!routingCtx.hasResult()) {
            // The content of a request that matched no route is never consumed, so '100 Continue' is never
            // written. Use a shared instance which regards it as suppressed.
            return multiplex ? UNSENDABLE_MULTIPLEX : UNSENDABLE;
        }
        return new ExpectContinueHandler(ctx, encoder, multiplex);
    }

    @Nullable
    private final ChannelHandlerContext ctx;
    @Nullable
    private final ServerHttpObjectEncoder encoder;
    private final boolean multiplex;

    @Nullable
    private DecodedHttpRequest req;
    // 'true' until '100 Continue' is written, skipped or suppressed.
    private boolean pending;
    // 'true' if '100 Continue' was not written although the request content was not fully received.
    private boolean suppressed;

    private ExpectContinueHandler(ChannelHandlerContext ctx, ServerHttpObjectEncoder encoder,
                                  boolean multiplex) {
        this.ctx = ctx;
        this.encoder = encoder;
        this.multiplex = multiplex;
        pending = true;
    }

    private ExpectContinueHandler(boolean multiplex) {
        ctx = null;
        encoder = null;
        this.multiplex = multiplex;
        suppressed = true;
    }

    /**
     * Sets the request whose {@code 100 Continue} response is handled by this handler. Must be called
     * before the request is passed to a service. No-op for the shared instance.
     */
    void init(DecodedHttpRequest req) {
        if (!pending) {
            return;
        }
        assert this.req == null;
        this.req = req;
    }

    /**
     * Invoked when the request content is subscribed to. May be called outside the event loop.
     */
    void onSubscribe() {
        // 'pending' may be read outside the event loop. It is fine because it is initialized before
        // the request is passed to a service and 'send0()' checks it again in the event loop.
        if (!pending) {
            return;
        }
        assert encoder != null;
        if (encoder.eventLoop().inEventLoop()) {
            send0();
        } else {
            encoder.eventLoop().execute(this::send0);
        }
    }

    /**
     * Writes a pending {@code 100 Continue} response immediately, because final response headers that allow
     * the client to send the content are about to be written.
     *
     * @return {@code true} if the connection has to be closed after the response, because
     *         {@code 100 Continue} was not written before the request content was fully received, e.g.
     *         the request was aborted, and the client may never send the content.
     */
    boolean send() {
        assert inEventLoop();
        send0();
        return shouldCloseConnection();
    }

    private void send0() {
        if (!pending) {
            return;
        }
        pending = false;
        final DecodedHttpRequest req = this.req;
        assert req != null;
        if (req.isOpen()) {
            assert ctx != null && encoder != null;
            encoder.writeHeaders(req.id(), req.streamId(), CONTINUE_RESPONSE, false, req.method());
            ctx.flush();
        } else {
            // The content has been fully received or the request was aborted.
            suppressed = !req.isClosedSuccessfully();
        }
    }

    /**
     * Discards a pending {@code 100 Continue} response because a service wrote it by itself.
     */
    void onContinueSent() {
        assert inEventLoop();
        if (!pending) {
            return;
        }
        pending = false;
    }

    /**
     * Prevents a pending {@code 100 Continue} response from being written because final response headers
     * that do not allow the client to send the content are about to be written.
     *
     * @return {@code true} if the connection has to be closed after the response, because
     *         {@code 100 Continue} was not written before the request content was fully received and
     *         the client may never send the content.
     */
    boolean suppress() {
        assert inEventLoop();
        if (pending) {
            pending = false;
            final DecodedHttpRequest req = this.req;
            assert req != null;
            suppressed = !req.isClosedSuccessfully();
        }
        return shouldCloseConnection();
    }

    /**
     * Returns whether {@code 100 Continue} was not written before the request content was fully received.
     */
    boolean isSuppressed() {
        return suppressed;
    }

    private boolean shouldCloseConnection() {
        return suppressed && !multiplex;
    }

    private boolean inEventLoop() {
        return encoder == null || encoder.eventLoop().inEventLoop();
    }
}
