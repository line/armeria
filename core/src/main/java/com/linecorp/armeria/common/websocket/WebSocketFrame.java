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

import static com.linecorp.armeria.common.websocket.ByteArrayWebSocketFrame.EMPTY_PING;
import static com.linecorp.armeria.common.websocket.ByteArrayWebSocketFrame.EMPTY_PONG;
import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;

import com.linecorp.armeria.common.Bytes;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.buffer.ByteBuf;

/**
 * A {@link WebSocketFrame} to send data to the peer.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5">Data framing</a>
 */
@UnstableApi
public interface WebSocketFrame extends Bytes {

    /**
     * Returns a new text {@link WebSocketFrame} with the text whose {@link #isFinalFragment()}
     * is set to {@code true}.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.6">Data Frames</a>
     */
    static WebSocketFrame ofText(String text) {
        return ofText(text, true);
    }

    /**
     * Returns a new text {@link WebSocketFrame} with the text and {@code finalFragment}. When the
     * {@code finalFragment} is {@code false}, {@link #ofContinuation(byte[])} frames must be followed.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.6">Data Frames</a>
     */
    static WebSocketFrame ofText(String text, boolean finalFragment) {
        requireNonNull(text, "text");
        return new ByteArrayWebSocketFrame(text.getBytes(StandardCharsets.UTF_8), WebSocketFrameType.TEXT,
                                           finalFragment, text);
    }

    /**
     * Returns a new text {@link WebSocketFrame} with the UTF-8 encoded text whose {@link #isFinalFragment()}
     * is set to {@code true}.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.6">Data Frames</a>
     */
    static WebSocketFrame ofText(byte[] text) {
        requireNonNull(text, "text");
        return ofText(text, true);
    }

    /**
     * Returns a new text {@link WebSocketFrame} with the UTF-8 encoded text and {@code finalFragment}.
     * When the {@code finalFragment} is {@code false}, {@link #ofContinuation(byte[])} frames
     * must be followed.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.6">Data Frames</a>
     */
    static WebSocketFrame ofText(byte[] text, boolean finalFragment) {
        requireNonNull(text, "text");
        return new ByteArrayWebSocketFrame(text, WebSocketFrameType.TEXT, finalFragment);
    }

    /**
     * Returns a new binary {@link WebSocketFrame} whose {@link #isFinalFragment()} is set to {@code true}.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.6">Data Frames</a>
     */
    static WebSocketFrame ofBinary(byte[] data) {
        requireNonNull(data, "data");
        return ofBinary(data, true);
    }

    /**
     * Returns a new binary {@link WebSocketFrame} with the {@code finalFragment}. When the
     * {@code finalFragment} is {@code false}, {@link #ofContinuation(byte[])} frames must be followed.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.6">Data Frames</a>
     */
    static WebSocketFrame ofBinary(byte[] data, boolean finalFragment) {
        requireNonNull(data, "data");
        return new ByteArrayWebSocketFrame(data, WebSocketFrameType.BINARY, finalFragment);
    }

    /**
     * Returns a new {@link CloseWebSocketFrame} with the {@link WebSocketCloseStatus}.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.5.1">Close</a>
     */
    static CloseWebSocketFrame ofClose(WebSocketCloseStatus status) {
        requireNonNull(status, "status");
        return ofClose(status, status.reasonPhrase());
    }

    /**
     * Returns a new {@link CloseWebSocketFrame} with the {@link WebSocketCloseStatus} and the reason.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.5.1">Close</a>
     */
    static CloseWebSocketFrame ofClose(WebSocketCloseStatus status, String reason) {
        requireNonNull(status, "status");
        requireNonNull(reason, "reason");
        return new CloseByteBufWebSocketFrame(status, reason);
    }

    /**
     * Returns a new {@link CloseWebSocketFrame} with the {@code data} that contains
     * {@link WebSocketCloseStatus}.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.5.1">Close</a>
     */
    static CloseWebSocketFrame ofClose(byte[] data) {
        requireNonNull(data, "data");
        return new CloseByteBufWebSocketFrame(data);
    }

    /**
     * Returns a ping {@link WebSocketFrame}.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.5.2">Ping</a>
     */
    static WebSocketFrame ofPing() {
        return EMPTY_PING;
    }

    /**
     * Returns a new ping {@link WebSocketFrame} with the {@code data}.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.5.2">Ping</a>
     */
    static WebSocketFrame ofPing(byte[] data) {
        requireNonNull(data, "data");
        if (data.length == 0) {
            return EMPTY_PING;
        }
        return new ByteArrayWebSocketFrame(data, WebSocketFrameType.PING);
    }

    /**
     * Returns a pong {@link WebSocketFrame}.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.5.3">Pong</a>
     */
    static WebSocketFrame ofPong() {
        return EMPTY_PONG;
    }

    /**
     * Returns a new pong {@link WebSocketFrame} with the {@code data}.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.5.3">Pong</a>
     */
    static WebSocketFrame ofPong(byte[] data) {
        requireNonNull(data, "data");
        if (data.length == 0) {
            return EMPTY_PONG;
        }
        return new ByteArrayWebSocketFrame(data, WebSocketFrameType.PING);
    }

    /**
     * Returns a new continuation {@link WebSocketFrame} whose {@link #isFinalFragment()}
     * is set to {@code true}.
     * {@code isText} must be {@code true} if this continuation frame follows a text frame.
     * {@code false} if this follows a binary frame.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.4">Fragmentation</a>
     */
    static WebSocketFrame ofContinuation(byte[] data) {
        return ofContinuation(data, true);
    }

    /**
     * Returns a new continuation {@link WebSocketFrame} with the data and {@code finalFragment}.
     * {@code isText} must be {@code true} if this continuation frame follows a text frame.
     * {@code false} if this follows a binary frame.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.4">Fragmentation</a>
     */
    static WebSocketFrame ofContinuation(byte[] data, boolean finalFragment) {
        requireNonNull(data, "data");
        return new ByteArrayWebSocketFrame(data, WebSocketFrameType.CONTINUATION, finalFragment);
    }

    /**
     * Returns a new text continuation {@link WebSocketFrame} whose {@link #isFinalFragment()}
     * is set to {@code true}.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.4">Fragmentation</a>
     */
    static WebSocketFrame ofContinuation(String text) {
        return ofContinuation(text, true);
    }

    /**
     * Returns a new text continuation {@link WebSocketFrame} with the text and {@code finalFragment}.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.4">Fragmentation</a>
     */
    static WebSocketFrame ofContinuation(String text, boolean finalFragment) {
        requireNonNull(text, "text");
        return new ByteArrayWebSocketFrame(text.getBytes(StandardCharsets.UTF_8),
                                           WebSocketFrameType.CONTINUATION, finalFragment, text);
    }

    /**
     * (Advanced users only) Returns a new text {@link WebSocketFrame} with the {@link ByteBuf}
     * whose {@link #isFinalFragment()} is set to {@code true}.
     *
     * @see #ofText(byte[])
     * @see PooledObjects
     */
    static WebSocketFrame ofPooledText(ByteBuf text) {
        requireNonNull(text, "text");
        return ofPooledText(text, true);
    }

    /**
     * (Advanced users only) Returns a new text {@link WebSocketFrame} with the {@link ByteBuf}
     * and {@code finalFragment}. When the {@code finalFragment} is {@code false},
     * {@link #ofPooledContinuation(ByteBuf)} frames must be followed.
     *
     * @see #ofText(byte[])
     * @see PooledObjects
     */
    static WebSocketFrame ofPooledText(ByteBuf text, boolean finalFragment) {
        requireNonNull(text, "text");
        return new ByteBufWebSocketFrame(text, WebSocketFrameType.TEXT, finalFragment);
    }

    /**
     * (Advanced users only) Returns a new binary {@link WebSocketFrame} with the {@link ByteBuf}
     * whose {@link #isFinalFragment()} is set to {@code true}.
     *
     * @see #ofBinary(byte[])
     * @see PooledObjects
     */
    static WebSocketFrame ofPooledBinary(ByteBuf data) {
        requireNonNull(data, "data");
        return ofPooledBinary(data, true);
    }

    /**
     * (Advanced users only) Returns a new binary {@link WebSocketFrame} with the {@link ByteBuf}
     * and {@code finalFragment}. When the {@code finalFragment} is {@code false},
     * {@link #ofPooledContinuation(ByteBuf)} frames must be followed.
     *
     * @see #ofBinary(byte[], boolean)
     * @see PooledObjects
     */
    static WebSocketFrame ofPooledBinary(ByteBuf data, boolean finalFragment) {
        requireNonNull(data, "data");
        return new ByteBufWebSocketFrame(data, WebSocketFrameType.BINARY, finalFragment);
    }

    /**
     * (Advanced users only) Returns a new {@link CloseWebSocketFrame} with the {@link ByteBuf}.
     *
     * @see #ofClose(byte[])
     * @see PooledObjects
     */
    static CloseWebSocketFrame ofPooledClose(ByteBuf data) {
        requireNonNull(data, "data");
        return new CloseByteBufWebSocketFrame(data, true);
    }

    /**
     * (Advanced users only) Returns a new ping {@link WebSocketFrame} with the {@link ByteBuf}.
     *
     * @see #ofPing(byte[])
     * @see PooledObjects
     */
    static WebSocketFrame ofPooledPing(ByteBuf data) {
        requireNonNull(data, "data");
        if (data.readableBytes() == 0) {
            data.release();
            return EMPTY_PING;
        }
        return new ByteBufWebSocketFrame(data, WebSocketFrameType.PING, true);
    }

    /**
     * (Advanced users only) Returns a new pong {@link WebSocketFrame} with the {@link ByteBuf}.
     *
     * @see #ofPong(byte[])
     * @see PooledObjects
     */
    static WebSocketFrame ofPooledPong(ByteBuf data) {
        requireNonNull(data, "data");
        if (data.readableBytes() == 0) {
            data.release();
            return EMPTY_PONG;
        }
        return new ByteBufWebSocketFrame(data, WebSocketFrameType.PONG, true);
    }

    /**
     * (Advanced users only) Returns a new continuation {@link WebSocketFrame} with the {@link ByteBuf}
     * whose {@link #isFinalFragment()} is set to {@code true}.
     * {@code isText} must be {@code true} if this continuation frame follows a text frame.
     * {@code false} if this follows a binary frame.
     *
     * @see #ofContinuation(byte[])
     * @see PooledObjects
     */
    static WebSocketFrame ofPooledContinuation(ByteBuf data) {
        return ofPooledContinuation(data, true);
    }

    /**
     * (Advanced users only) Returns a new binary {@link WebSocketFrame} with the {@link ByteBuf}
     * and {@code finalFragment}.
     * {@code isText} must be {@code true} if this continuation frame follows a text frame.
     * {@code false} if this follows a binary frame.
     *
     * @see #ofContinuation(byte[])
     * @see PooledObjects
     */
    static WebSocketFrame ofPooledContinuation(ByteBuf data, boolean finalFragment) {
        requireNonNull(data, "data");
        return new ByteBufWebSocketFrame(data, WebSocketFrameType.CONTINUATION,
                                         finalFragment);
    }

    /**
     * Returns the type of this frame.
     */
    WebSocketFrameType type();

    /**
     * Tells whether this frame is a final fragment or not.
     *
     * @see #ofContinuation(byte[])
     */
    boolean isFinalFragment();

    /**
     * Returns the text data in this frame.
     */
    String text();
}
