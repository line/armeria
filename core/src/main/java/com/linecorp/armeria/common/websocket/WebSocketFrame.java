/*
 * Copyright 2021 LINE Corporation
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

import static com.linecorp.armeria.common.websocket.PingWebSocketFrame.emptyPing;
import static com.linecorp.armeria.common.websocket.PongWebSocketFrame.emptyPong;
import static java.util.Objects.requireNonNull;

import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.ByteBufAccessMode;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.buffer.ByteBuf;

/**
 * A {@link WebSocketFrame} to send data to the peer.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5">Data framing</a>
 */
@UnstableApi
public interface WebSocketFrame extends SafeCloseable {

    /**
     * Returns a new {@link TextWebSocketFrame} with the text whose {@code finalFragment}
     * is set to {@code true}.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.6">Data Frames</a>
     */
    static TextWebSocketFrame ofText(String text) {
        return ofText(text, true);
    }

    /**
     * Returns a new {@link TextWebSocketFrame} with the text and {@code finalFragment}. When the
     * {@code finalFragment} is {@code false}, {@link #ofContinuation(byte[])} frames must be followed.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.6">Data Frames</a>
     */
    static TextWebSocketFrame ofText(String text, boolean finalFragment) {
        requireNonNull(text, "text");
        return new TextWebSocketFrame(text, finalFragment);
    }

    /**
     * Returns a new {@link TextWebSocketFrame} with the UTF-8 encoded text whose {@code finalFragment} is set
     * to {@code true}.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.6">Data Frames</a>
     */
    static TextWebSocketFrame ofText(byte[] text) {
        requireNonNull(text, "text");
        return ofText(text, true);
    }

    /**
     * Returns a new {@link TextWebSocketFrame} with the UTF-8 encoded text and {@code finalFragment}.
     * When the {@code finalFragment} is {@code false}, {@link #ofContinuation(byte[])} frames must be followed.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.6">Data Frames</a>
     */
    static TextWebSocketFrame ofText(byte[] text, boolean finalFragment) {
        requireNonNull(text, "text");
        return new TextWebSocketFrame(text, finalFragment);
    }

    /**
     * Returns a new binary {@link WebSocketFrame} whose {@code finalFragment} is set to {@code true}.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.6">Data Frames</a>
     */
    static WebSocketFrame ofBinary(byte[] binary) {
        requireNonNull(binary, "binary");
        return ofBinary(binary, true);
    }

    /**
     * Returns a new binary {@link WebSocketFrame} with the {@code finalFragment}. When the
     * {@code finalFragment} is {@code false}, {@link #ofContinuation(byte[])} frames must be followed.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.6">Data Frames</a>
     */
    static WebSocketFrame ofBinary(byte[] binary, boolean finalFragment) {
        requireNonNull(binary, "binary");
        return new BinaryWebSocketFrame(binary, finalFragment);
    }

    /**
     * Returns a new {@link CloseWebSocketFrame} with the {@link WebSocketCloseStatus}.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.5.1">Close</a>
     */
    static CloseWebSocketFrame ofClose(WebSocketCloseStatus status) {
        requireNonNull(status, "status");
        return ofClose(status, status.reasonText());
    }

    /**
     * Returns a new {@link CloseWebSocketFrame} with the {@link WebSocketCloseStatus} and the reason.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.5.1">Close</a>
     */
    static CloseWebSocketFrame ofClose(WebSocketCloseStatus status, String reason) {
        requireNonNull(status, "status");
        requireNonNull(reason, "reason");
        return new CloseWebSocketFrame(status, reason);
    }

    /**
     * Returns a new {@link CloseWebSocketFrame} with the {@code binary}.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.5.1">Close</a>
     */
    static CloseWebSocketFrame ofClose(byte[] binary) {
        requireNonNull(binary, "binary");
        return new CloseWebSocketFrame(binary);
    }

    /**
     * Returns a ping {@link WebSocketFrame}.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.5.2">Ping</a>
     */
    static WebSocketFrame ofPing() {
        return emptyPing;
    }

    /**
     * Returns a new ping {@link WebSocketFrame} with the {@code binary}.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.5.2">Ping</a>
     */
    static WebSocketFrame ofPing(byte[] binary) {
        requireNonNull(binary, "binary");
        if (binary.length == 0) {
            return emptyPing;
        }
        return new PingWebSocketFrame(binary);
    }

    /**
     * Returns a pong {@link WebSocketFrame}.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.5.3">Pong</a>
     */
    static WebSocketFrame ofPong() {
        return emptyPong;
    }

    /**
     * Returns a new pong {@link WebSocketFrame} with the {@code binary}.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.5.3">Pong</a>
     */
    static WebSocketFrame ofPong(byte[] binary) {
        requireNonNull(binary, "binary");
        if (binary.length == 0) {
            return emptyPong;
        }
        return new PongWebSocketFrame(binary);
    }

    /**
     * Returns a new continuation {@link WebSocketFrame} whose {@code finalFragment} is set to {@code true}.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.4">Fragmentation</a>
     */
    static WebSocketFrame ofContinuation(byte[] binary) {
        return ofContinuation(binary, true);
    }

    /**
     * Returns a new continuation {@link WebSocketFrame} with the binary and {@code finalFragment}.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.4">Fragmentation</a>
     */
    static WebSocketFrame ofContinuation(byte[] binary, boolean frameFinalFlag) {
        requireNonNull(binary, "binary");
        return new ContinuationWebSocketFrame(binary, frameFinalFlag);
    }

    /**
     * (Advanced users only) Returns a new {@link TextWebSocketFrame} with the {@link ByteBuf}
     * whose {@code finalFragment} is set to {@code true}.
     *
     * @see #ofText(byte[])
     * @see PooledObjects
     */
    static TextWebSocketFrame ofPooledText(ByteBuf text) {
        requireNonNull(text, "text");
        return ofPooledText(text, true);
    }

    /**
     * (Advanced users only) Returns a new {@link TextWebSocketFrame} with the {@link ByteBuf}
     * and {@code finalFragment}. When the {@code finalFragment} is {@code false},
     * {@link #ofPooledContinuation(ByteBuf)} frames must be followed.
     *
     * @see #ofText(byte[])
     * @see PooledObjects
     */
    static TextWebSocketFrame ofPooledText(ByteBuf text, boolean finalFragment) {
        requireNonNull(text, "text");
        return new TextWebSocketFrame(text, finalFragment);
    }

    /**
     * (Advanced users only) Returns a new binary {@link WebSocketFrame} with the {@link ByteBuf}
     * whose {@code finalFragment} is set to {@code true}.
     *
     * @see #ofBinary(byte[])
     * @see PooledObjects
     */
    static WebSocketFrame ofPooledBinary(ByteBuf binary) {
        requireNonNull(binary, "binary");
        return ofPooledBinary(binary, true);
    }

    /**
     * (Advanced users only) Returns a new binary {@link WebSocketFrame} with the {@link ByteBuf}
     * and {@code finalFragment}. When the {@code finalFragment} is {@code false},
     * {@link #ofPooledContinuation(ByteBuf)} frames must be followed.
     *
     * @see #ofBinary(byte[], boolean)
     * @see PooledObjects
     */
    static WebSocketFrame ofPooledBinary(ByteBuf binary, boolean finalFragment) {
        requireNonNull(binary, "binary");
        return new BinaryWebSocketFrame(binary, finalFragment);
    }

    /**
     * (Advanced users only) Returns a new {@link CloseWebSocketFrame} with the {@link ByteBuf}.
     *
     * @see #ofClose(byte[])
     * @see PooledObjects
     */
    static CloseWebSocketFrame ofPooledClose(ByteBuf binary) {
        requireNonNull(binary, "binary");
        return new CloseWebSocketFrame(binary);
    }

    /**
     * (Advanced users only) Returns a new ping {@link WebSocketFrame} with the {@link ByteBuf}.
     *
     * @see #ofPing(byte[])
     * @see PooledObjects
     */
    static WebSocketFrame ofPooledPing(ByteBuf binary) {
        requireNonNull(binary, "binary");
        if (binary.readableBytes() == 0) {
            return emptyPing;
        }
        return new PingWebSocketFrame(binary);
    }

    /**
     * (Advanced users only) Returns a new pong {@link WebSocketFrame} with the {@link ByteBuf}.
     *
     * @see #ofPong(byte[])
     * @see PooledObjects
     */
    static WebSocketFrame ofPooledPong(ByteBuf binary) {
        requireNonNull(binary, "binary");
        if (binary.readableBytes() == 0) {
            return emptyPong;
        }
        return new PongWebSocketFrame(binary);
    }

    /**
     * (Advanced users only) Returns a new continuation {@link WebSocketFrame} with the {@link ByteBuf}
     * whose {@code finalFragment} is set to {@code true}.
     *
     * @see #ofContinuation(byte[])
     * @see PooledObjects
     */
    static WebSocketFrame ofPooledContinuation(ByteBuf binary) {
        return ofPooledContinuation(binary, true);
    }

    /**
     * (Advanced users only) Returns a new binary {@link WebSocketFrame} with the {@link ByteBuf}
     * and {@code finalFragment}.
     *
     * @see #ofContinuation(byte[], boolean)
     * @see PooledObjects
     */
    static WebSocketFrame ofPooledContinuation(ByteBuf binary, boolean frameFinalFlag) {
        requireNonNull(binary, "binary");
        return new ContinuationWebSocketFrame(binary, frameFinalFlag);
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
     * Returns the underlying byte array of the payload data. Any changes made in the returned array affects
     * the content of the payload data.
     */
    byte[] array();

    /**
     * Returns the length of the payload data.
     */
    int dataLength();

    /**
     * (Advanced users only) Returns whether the payload data is pooled. Note, if this method returns
     * {@code true}, you must call {@link #close()} once you no longer need the payload data,
     * because its underlying {@link ByteBuf} will not be released automatically.
     *
     * @see PooledObjects
     */
    boolean isPooled();

    /**
     * (Advanced users only) Returns a new duplicate of the underlying {@link ByteBuf} of this data.
     * This method does not transfer the ownership of the underlying {@link ByteBuf}, i.e. the reference
     * count of the {@link ByteBuf} does not change. If this data is not pooled, the returned {@link ByteBuf}
     * is not pooled, either, which means you need to worry about releasing it only when you created this data
     * with via {@code ofPooled} methods. Any changes made in the content of the returned {@link ByteBuf}
     * affects the content of this data.
     *
     * @see PooledObjects
     */
    default ByteBuf byteBuf() {
        return byteBuf(ByteBufAccessMode.DUPLICATE);
    }

    /**
     * (Advanced users only) Returns a new duplicate of the underlying {@link ByteBuf} of the payload data.
     * This method does not transfer the ownership of the underlying {@link ByteBuf}, i.e. the reference
     * count of the {@link ByteBuf} does not change. If the payload data is not pooled,
     * the returned {@link ByteBuf} is not pooled, either, which means you need to worry about releasing it
     * only when you created the payload datavia {@code ofPooled} methods.
     * Any changes made in the content of the returned {@link ByteBuf} affects the content of the payload data.
     *
     * @see PooledObjects
     */
    ByteBuf byteBuf(ByteBufAccessMode mode);

    /**
     * Releases the underlying {@link ByteBuf} if this frame was created via {@code ofPooled} methods.
     * Otherwise, this method does nothing. You may want to call this method to reclaim the underlying
     * {@link ByteBuf} when using operations that return pooled objects, such as:
     * <ul>
     *   <li>{@link WebSocket#subscribe(Subscriber, SubscriptionOption...)} with
     *       {@link SubscriptionOption#WITH_POOLED_OBJECTS}</li>
     * </ul>
     * If you don't use such operations, you don't need to call this method.
     *
     * @see PooledObjects
     */
    @Override
    void close();
}
