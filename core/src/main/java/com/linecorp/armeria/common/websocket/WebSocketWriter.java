/*
 * Copyright 2022 LINE Corporation
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
package com.linecorp.armeria.common.websocket;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.stream.StreamMessageAndWriter;

/**
 * A {@link WebSocket} that you can write {@link WebSocketFrame}s to it.
 * Use {@link WebSocket#streaming()} to construct.
 */
@UnstableApi
public interface WebSocketWriter extends WebSocket, StreamMessageAndWriter<WebSocketFrame> {

    /**
     * Write a text {@link WebSocketFrame} to this {@link WebSocket}.
     *
     * @see WebSocketFrame#ofText(String)
     */
    default void write(String text) {
        write(text, true);
    }

    /**
     * Write a text {@link WebSocketFrame} to this {@link WebSocket} whose
     * {@link WebSocketFrame#isFinalFragment()} is set to {@code finalFragment}.
     *
     * @see WebSocketFrame#ofText(String, boolean)
     */
    default void write(String text, boolean finalFragment) {
        write(WebSocketFrame.ofText(text, finalFragment));
    }

    /**
     * Write a binary {@link WebSocketFrame} to this {@link WebSocket}.
     *
     * @see WebSocketFrame#ofBinary(byte[])
     */
    default void write(byte[] data) {
        write(data, true);
    }

    /**
     * Write a binary {@link WebSocketFrame} to this {@link WebSocket} whose
     * {@link WebSocketFrame#isFinalFragment()} is set to {@code finalFragment}.
     *
     * @see WebSocketFrame#ofBinary(byte[], boolean)
     */
    default void write(byte[] data, boolean finalFragment) {
        write(WebSocketFrame.ofBinary(data, finalFragment));
    }

    /**
     * Write a text {@link WebSocketFrame} to this {@link WebSocket}.
     *
     * @return {@code true} if the text has been scheduled for publication. {@code false} if the
     *         writer has been closed already.
     * @see WebSocketFrame#ofText(String)
     */
    default boolean tryWrite(String text) {
        return tryWrite(text, true);
    }

    /**
     * Write a text {@link WebSocketFrame} to this {@link WebSocket}.
     *
     * @return {@code true} if the text has been scheduled for publication. {@code false} if the
     *         writer has been closed already.
     * @see WebSocketFrame#ofText(String)
     */
    default boolean tryWrite(String text, boolean finalFragment) {
        return tryWrite(WebSocketFrame.ofText(text, finalFragment));
    }

    /**
     * Write a binary {@link WebSocketFrame} to this {@link WebSocket}.
     *
     * @return {@code true} if the data has been scheduled for publication. {@code false} if the
     *         writer has been closed already.
     * @see WebSocketFrame#ofBinary(byte[])
     */
    default boolean tryWrite(byte[] data) {
        return tryWrite(data, true);
    }

    /**
     * Write a binary {@link WebSocketFrame} to this {@link WebSocket}.
     *
     * @return {@code true} if the data has been scheduled for publication. {@code false} if the
     *         writer has been closed already.
     * @see WebSocketFrame#ofBinary(byte[])
     */
    default boolean tryWrite(byte[] data, boolean finalFragment) {
        return tryWrite(WebSocketFrame.ofBinary(data, finalFragment));
    }

    /**
     * Write a ping {@link WebSocketFrame} to this {@link WebSocket}.
     *
     * @see WebSocketFrame#ofPing()
     */
    default void ping() {
        write(WebSocketFrame.ofPing());
    }

    /**
     * Write a ping {@link WebSocketFrame} to this {@link WebSocket} with the data.
     *
     * @see WebSocketFrame#ofPing(byte[])
     */
    default void ping(byte[] data) {
        write(WebSocketFrame.ofPing(data));
    }

    /**
     * Write a pong {@link WebSocketFrame} to this {@link WebSocket}.
     *
     * @see WebSocketFrame#ofPong()
     */
    default void pong() {
        write(WebSocketFrame.ofPong());
    }

    /**
     * Write a pong {@link WebSocketFrame} to this {@link WebSocket} with the data.
     *
     * @see WebSocketFrame#ofPong(byte[])
     */
    default void pong(byte[] data) {
        write(WebSocketFrame.ofPong(data));
    }

    /**
     * Sends the closing handshake with {@link WebSocketCloseStatus#NORMAL_CLOSURE}.
     */
    @Override
    void close();

    /**
     * Sends the closing handshake with the {@link WebSocketCloseStatus}.
     */
    void close(WebSocketCloseStatus status);

    /**
     * Sends the closing handshake with the {@link WebSocketCloseStatus} and the reason.
     */
    void close(WebSocketCloseStatus status, String reasonPhrase);

    /**
     * Sends the closing handshake with the {@link WebSocketCloseStatus#INTERNAL_SERVER_ERROR} and
     * {@link Throwable#getMessage()}.
     */
    @Override
    void close(Throwable cause);
}
