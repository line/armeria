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

import java.util.function.BooleanSupplier;

import io.netty.channel.EventLoop;

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
 * It is suppressed if other final response headers are written first, and it is not written by this handler
 * if a service writes {@code 100 Continue} by itself.
 *
 * <p>All methods except {@link #onSubscribe()} must be called from the event loop.
 */
final class ExpectContinueHandler {

    private final EventLoop eventLoop;
    private final Runnable writeContinue;
    private final BooleanSupplier requestOpen;
    private final BooleanSupplier contentReceived;

    // 'true' until '100 Continue' is written, skipped or suppressed.
    private boolean pending = true;
    private boolean suppressed;

    /**
     * Creates a new instance.
     *
     * @param writeContinue the task that writes a {@code 100 Continue} response
     * @param requestOpen whether the request is still open. {@code 100 Continue} is not written if
     *                    the content has been fully received or the request was aborted.
     * @param contentReceived whether the request content has been fully received
     */
    ExpectContinueHandler(EventLoop eventLoop, Runnable writeContinue, BooleanSupplier requestOpen,
                          BooleanSupplier contentReceived) {
        this.eventLoop = eventLoop;
        this.writeContinue = writeContinue;
        this.requestOpen = requestOpen;
        this.contentReceived = contentReceived;
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
        if (eventLoop.inEventLoop()) {
            send0();
        } else {
            eventLoop.execute(this::send0);
        }
    }

    /**
     * Writes a pending {@code 100 Continue} response immediately, because final response headers that allow
     * the client to send the content are about to be written.
     */
    void send() {
        assert eventLoop.inEventLoop();
        send0();
    }

    private void send0() {
        if (!pending) {
            return;
        }
        pending = false;
        if (requestOpen.getAsBoolean()) {
            writeContinue.run();
        }
    }

    /**
     * Discards a pending {@code 100 Continue} response because a service wrote it by itself.
     */
    void onContinueSent() {
        assert eventLoop.inEventLoop();
        pending = false;
    }

    /**
     * Prevents a pending {@code 100 Continue} response from being written because final response headers
     * that do not allow the client to send the content are about to be written.
     *
     * @return {@code true} if a pending {@code 100 Continue} response was suppressed before the request
     *         content was fully received, which means the client may never send the content.
     */
    boolean suppress() {
        assert eventLoop.inEventLoop();
        if (!pending) {
            return false;
        }
        pending = false;
        suppressed = !contentReceived.getAsBoolean();
        return suppressed;
    }

    /**
     * Returns whether a {@code 100 Continue} response was suppressed before the request content was fully
     * received.
     */
    boolean isSuppressed() {
        return suppressed;
    }
}
