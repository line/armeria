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
/*
 * Copyright 2019 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
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

package com.linecorp.armeria.internal.common.websocket;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.Bytes;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.HttpDecoder;
import com.linecorp.armeria.common.stream.StreamDecoderInput;
import com.linecorp.armeria.common.stream.StreamDecoderOutput;
import com.linecorp.armeria.common.websocket.CloseWebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.common.websocket.WebSocketCloseStatus;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketFrameType;
import com.linecorp.armeria.server.websocket.WebSocketProtocolViolationException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public abstract class WebSocketFrameDecoder implements HttpDecoder<WebSocketFrame> {

    // Forked from Netty 4.1.92 https://github.com/netty/netty/blob/e8df52e442629214e0355528c00e873e213f0139/codec-http/src/main/java/io/netty/handler/codec/http/websocketx/WebSocket08FrameDecoder.java

    private static final Logger logger = LoggerFactory.getLogger(WebSocketFrameDecoder.class);

    enum State {
        READING_FIRST,
        READING_SECOND,
        READING_SIZE,
        MASKING_KEY,
        PAYLOAD,
        CORRUPT
    }

    private final int maxFramePayloadLength;
    private final boolean allowMaskMismatch;
    private final boolean aggregateContinuation;
    private final List<WebSocketFrame> aggregatingFrames = new ArrayList<>();
    private long aggregatingFramesLength;
    @Nullable
    private WebSocket outboundFrames;

    private int fragmentedFramesCount;
    private boolean finalFragment;
    private boolean frameMasked;
    private int frameRsv;
    private int frameOpcode;
    private long framePayloadLength;
    private int mask;
    private int framePayloadLen1;
    private boolean receivedClosingHandshake;
    private State state = State.READING_FIRST;

    protected WebSocketFrameDecoder(int maxFramePayloadLength, boolean allowMaskMismatch,
                                    boolean aggregateContinuation) {
        this.maxFramePayloadLength = maxFramePayloadLength;
        this.allowMaskMismatch = allowMaskMismatch;
        this.aggregateContinuation = aggregateContinuation;
    }

    public void setOutboundWebSocket(WebSocket outboundFrames) {
        this.outboundFrames = outboundFrames;
    }

    @Override
    public void process(StreamDecoderInput in, StreamDecoderOutput<WebSocketFrame> out) throws Exception {
        while (in.readableBytes() > 0) {
            // Discard all data received if closing handshake was received before.
            if (receivedClosingHandshake) {
                in.close();
                return;
            }

            switch (state) {
                case READING_FIRST:
                    framePayloadLength = 0;

                    // FIN, RSV, OPCODE
                    //noinspection LocalVariableUsedAndDeclaredInDifferentSwitchBranches
                    byte b = in.readByte();
                    finalFragment = (b & 0x80) != 0;
                    // TODO(minwoox): support rsv
                    frameRsv = (b & 0x70) >> 4;
                    frameOpcode = b & 0x0F;

                    logger.trace("Decoding a WebSocket Frame. opcode: {}, finalFragment: {}",
                                 frameOpcode, finalFragment);

                    state = State.READING_SECOND;
                    // fallthrough
                case READING_SECOND:
                    if (in.readableBytes() == 0) {
                        return;
                    }
                    // MASK, PAYLOAD LEN 1
                    b = in.readByte();
                    frameMasked = (b & 0x80) != 0;
                    framePayloadLen1 = b & 0x7F;

                    if (frameRsv != 0) { // TODO(minwoox): support extension
                        throw protocolViolation("RSV != 0 and no extension negotiated, RSV:" + frameRsv);
                    }

                    if (!allowMaskMismatch && expectMaskedFrames() != frameMasked) {
                        throw protocolViolation("received a frame that is not masked as expected");
                    }

                    if (frameOpcode > 7) { // control frame (have MSB in opcode set)

                        // control frames MUST NOT be fragmented
                        if (!finalFragment) {
                            throw protocolViolation("fragmented control frame");
                        }

                        // control frames MUST have payload 125 octets or less
                        if (framePayloadLen1 > 125) {
                            throw protocolViolation("control frame with payload length > 125 octets");
                        }

                        // check for reserved control frame opcodes
                        if (!(frameOpcode == WebSocketFrameType.CLOSE.opcode() ||
                              frameOpcode == WebSocketFrameType.PING.opcode() ||
                              frameOpcode == WebSocketFrameType.PONG.opcode())) {
                            throw protocolViolation("control frame using reserved opcode " + frameOpcode);
                        }

                        // close frame : if there is a body, the first two bytes of the
                        // body MUST be a 2-byte unsigned integer representing a getStatus code
                        if (frameOpcode == WebSocketFrameType.CLOSE.opcode() && framePayloadLen1 == 1) {
                            throw protocolViolation("received close control frame with payload len 1");
                        }
                    } else { // data frame
                        // check for reserved data frame opcodes
                        if (!(frameOpcode == WebSocketFrameType.CONTINUATION.opcode() ||
                              frameOpcode == WebSocketFrameType.TEXT.opcode() ||
                              frameOpcode == WebSocketFrameType.BINARY.opcode())) {
                            throw protocolViolation("data frame using reserved opcode " + frameOpcode);
                        }

                        if (fragmentedFramesCount == 0) {
                            if (frameOpcode == WebSocketFrameType.CONTINUATION.opcode()) {
                                throw protocolViolation("received continuation data frame " +
                                                        "outside fragmented message");
                            }
                        } else if (frameOpcode != WebSocketFrameType.CONTINUATION.opcode()) {
                            throw protocolViolation(
                                    "received non-continuation data frame while inside fragmented message");
                        }
                    }

                    state = State.READING_SIZE;
                    // fallthrough
                case READING_SIZE:

                    // Read frame payload length
                    if (framePayloadLen1 == 126) {
                        if (in.readableBytes() < 2) {
                            return;
                        }
                        framePayloadLength = in.readUnsignedShort();
                        if (framePayloadLength < 126) {
                            throw protocolViolation(
                                    "invalid data frame length (not using minimal length encoding)");
                        }
                    } else if (framePayloadLen1 == 127) {
                        if (in.readableBytes() < 8) {
                            return;
                        }
                        framePayloadLength = in.readLong();
                        if (framePayloadLength < 0) {
                            throw protocolViolation("invalid data frame length (negative length)");
                        }

                        if (framePayloadLength < 65536) {
                            throw protocolViolation(
                                    "invalid data frame length (not using minimal length encoding)");
                        }
                    } else {
                        framePayloadLength = framePayloadLen1;
                    }

                    if (framePayloadLength > maxFramePayloadLength) {
                        throw protocolViolation(WebSocketCloseStatus.MESSAGE_TOO_BIG,
                                                "Max frame length of " + maxFramePayloadLength +
                                                " has been exceeded.");
                    }

                    logger.trace("Decoding a WebSocket Frame. length: {}", framePayloadLength);

                    state = State.MASKING_KEY;
                    // fallthrough
                case MASKING_KEY:
                    if (frameMasked) {
                        if (in.readableBytes() < 4) {
                            return;
                        }
                        mask = in.readInt();
                    }
                    state = State.PAYLOAD;
                    // fallthrough
                case PAYLOAD:
                    if (in.readableBytes() < framePayloadLength) {
                        return;
                    }

                    ByteBuf payloadBuffer = Unpooled.EMPTY_BUFFER;
                    if (framePayloadLength > 0) {
                        payloadBuffer = in.readBytes(toFrameLength(framePayloadLength));
                    }

                    // Now we have all the data, the next checkpoint must be the next
                    // frame
                    state = State.READING_FIRST;

                    // Unmask data if needed
                    if (frameMasked & framePayloadLength > 0) {
                        unmask(payloadBuffer);
                    }

                    // Processing ping/pong/close frames because they cannot be
                    // fragmented
                    if (frameOpcode == WebSocketFrameType.PING.opcode()) {
                        final WebSocketFrame decodedFrame = WebSocketFrame.ofPooledPing(payloadBuffer);
                        out.add(decodedFrame);
                        logger.trace("{} is decoded.", decodedFrame);
                        continue; // to while loop
                    }

                    assert payloadBuffer != null;
                    if (frameOpcode == WebSocketFrameType.PONG.opcode()) {
                        final WebSocketFrame decodedFrame = WebSocketFrame.ofPooledPong(payloadBuffer);
                        out.add(decodedFrame);
                        logger.trace("{} is decoded.", decodedFrame);
                        continue; // to while loop
                    }
                    if (frameOpcode == WebSocketFrameType.CLOSE.opcode()) {
                        receivedClosingHandshake = true;
                        validateCloseFrame(payloadBuffer);
                        final CloseWebSocketFrame decodedFrame = WebSocketFrame.ofPooledClose(payloadBuffer);
                        out.add(decodedFrame);
                        logger.trace("{} is decoded.", decodedFrame);
                        onCloseFrameRead();
                        continue; // to while loop
                    }

                    if (frameOpcode != WebSocketFrameType.TEXT.opcode() &&
                        frameOpcode != WebSocketFrameType.BINARY.opcode() &&
                        frameOpcode != WebSocketFrameType.CONTINUATION.opcode()) {
                        throw protocolViolation(WebSocketCloseStatus.INVALID_MESSAGE_TYPE,
                                                "Cannot decode a web socket frame with opcode: " + frameOpcode);
                    }

                    final WebSocketFrame decodedFrame;
                    if (frameOpcode == WebSocketFrameType.TEXT.opcode()) {
                        decodedFrame = WebSocketFrame.ofPooledText(payloadBuffer, finalFragment);
                    } else if (frameOpcode == WebSocketFrameType.BINARY.opcode()) {
                        decodedFrame = WebSocketFrame.ofPooledBinary(payloadBuffer, finalFragment);
                    } else {
                        assert frameOpcode == WebSocketFrameType.CONTINUATION.opcode();
                        decodedFrame = WebSocketFrame.ofPooledContinuation(payloadBuffer, finalFragment);
                    }
                    logger.trace("{} is decoded.", decodedFrame);

                    if (finalFragment) {
                        fragmentedFramesCount = 0;
                        aggregatingFramesLength = 0;
                        if (aggregatingFrames.isEmpty()) {
                            out.add(decodedFrame);
                        } else {
                            aggregatingFrames.add(decodedFrame);
                            final ByteBuf[] byteBufs = aggregatingFrames.stream()
                                                                        .map(Bytes::byteBuf)
                                                                        .toArray(ByteBuf[]::new);
                            if (aggregatingFrames.get(0).type() == WebSocketFrameType.TEXT) {
                                out.add(WebSocketFrame.ofPooledText(Unpooled.wrappedBuffer(byteBufs), true));
                            } else {
                                out.add(WebSocketFrame.ofPooledBinary(Unpooled.wrappedBuffer(byteBufs), true));
                            }
                            aggregatingFrames.clear();
                        }
                    } else {
                        fragmentedFramesCount++;
                        if (aggregateContinuation) {
                            aggregatingFramesLength += framePayloadLength;
                            aggregatingFrames.add(decodedFrame);
                            if (aggregatingFramesLength > maxFramePayloadLength) {
                                // decodedFrame is release in processOnError.
                                throw protocolViolation(
                                        WebSocketCloseStatus.MESSAGE_TOO_BIG,
                                        "The length of aggregated frames exceeded the max frame length. " +
                                        " aggregated length: " + aggregatingFramesLength +
                                        ", max frame length: " + maxFramePayloadLength);
                            }
                        } else {
                            out.add(decodedFrame);
                        }
                    }
                    continue; // to while loop
                default:
                    throw new Error("Shouldn't reach here.");
            }
        }
    }

    protected abstract boolean expectMaskedFrames();

    protected abstract void onCloseFrameRead();

    private void unmask(ByteBuf frame) {
        long longMask = mask & 0xFFFFFFFFL;
        longMask |= longMask << 32;

        int i = frame.readerIndex();
        final int end = frame.writerIndex();
        for (final int lim = end - 7; i < lim; i += 8) {
            frame.setLong(i, frame.getLong(i) ^ longMask);
        }

        if (i < end - 3) {
            frame.setInt(i, frame.getInt(i) ^ (int) longMask);
            i += 4;
        }

        int maskOffset = 0;
        for (; i < end; i++) {
            frame.setByte(i, frame.getByte(i) ^ WebSocketUtil.byteAtIndex(mask, maskOffset++ & 3));
        }
    }

    private WebSocketProtocolViolationException protocolViolation(String message) {
        return protocolViolation(WebSocketCloseStatus.PROTOCOL_ERROR, message);
    }

    private WebSocketProtocolViolationException protocolViolation(WebSocketCloseStatus status, String message) {
        state = State.CORRUPT;
        return new WebSocketProtocolViolationException(status, message);
    }

    private static int toFrameLength(long l) {
        // We know that the length is less or equal to Integer.MAX_VALUE because maxFramePayloadLength
        // is an integer.
        return (int) l;
    }

    private void validateCloseFrame(ByteBuf buffer) {
        try {
            if (buffer.readableBytes() < 2) {
                throw protocolViolation(WebSocketCloseStatus.INVALID_PAYLOAD_DATA, "Invalid close frame body");
            }

            // Must have 2 byte integer within the valid range
            final int statusCode = buffer.getShort(buffer.readerIndex());
            if (!WebSocketCloseStatus.isValidStatusCode(statusCode)) {
                throw protocolViolation("Invalid close frame status code: " + statusCode);
            }

            // May have UTF-8 message
            if (buffer.readableBytes() > 2) {
                try {
                    new Utf8Validator().check(buffer, buffer.readerIndex() + 2, buffer.readableBytes() - 2);
                } catch (IllegalArgumentException ex) {
                    throw protocolViolation(WebSocketCloseStatus.INVALID_PAYLOAD_DATA, "bytes are not UTF-8");
                }
            }
        } catch (Exception e) {
            buffer.release();
            throw e;
        }
    }

    @Override
    public void processOnComplete(StreamDecoderInput in, StreamDecoderOutput<WebSocketFrame> out)
            throws Exception {
        cleanup();
    }

    @Override
    public void processOnError(Throwable cause) {
        cleanup();
        // If an exception from the inbound stream is raised after receiving a close frame,
        // we should not abort the outbound stream.
        if (!receivedClosingHandshake) {
            if (outboundFrames != null) {
                outboundFrames.abort(cause);
            }
        }
        onProcessOnError(cause);
    }

    protected void onProcessOnError(Throwable cause) {}

    private void cleanup() {
        if (!aggregatingFrames.isEmpty()) {
            for (WebSocketFrame frame : aggregatingFrames) {
                frame.close();
            }
            aggregatingFrames.clear();
        }
    }
}
