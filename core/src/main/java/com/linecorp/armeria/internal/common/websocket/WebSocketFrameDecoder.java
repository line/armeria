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
package com.linecorp.armeria.internal.common.websocket;

import static com.linecorp.armeria.internal.common.websocket.WebSocketUtil.closeWebSocketInboundStream;
import static java.util.Objects.requireNonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.HttpDecoder;
import com.linecorp.armeria.common.stream.StreamDecoderInput;
import com.linecorp.armeria.common.stream.StreamDecoderOutput;
import com.linecorp.armeria.common.websocket.WebSocketCloseStatus;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketFrameType;
import com.linecorp.armeria.server.websocket.WebSocketProtocolViolationException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;

public final class WebSocketFrameDecoder implements HttpDecoder<WebSocketFrame> {

    // Forked from Netty 4.1.85 at 7cc84285ea6f90f6af62fa465d1aafbbc497e889

    private static final Logger logger = LoggerFactory.getLogger(WebSocketFrameDecoder.class);

    enum State {
        READING_FIRST,
        READING_SECOND,
        READING_SIZE,
        MASKING_KEY,
        PAYLOAD,
        CORRUPT
    }

    private final RequestContext ctx;
    private final int maxFramePayloadLength;
    private final boolean allowMaskMismatch;
    private final HttpResponseWriter writer;
    private final WebSocketFrameEncoder encoder;
    private final WebSocketCloseHandler webSocketCloseHandler;
    private final boolean expectMaskedFrames;

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

    public WebSocketFrameDecoder(RequestContext ctx, int maxFramePayloadLength, boolean allowMaskMismatch,
                                 HttpResponseWriter writer, WebSocketFrameEncoder encoder,
                                 WebSocketCloseHandler webSocketCloseHandler,
                                 boolean expectMaskedFrames) {
        this.ctx = requireNonNull(ctx, "ctx");
        this.maxFramePayloadLength = maxFramePayloadLength;
        this.allowMaskMismatch = allowMaskMismatch;
        this.writer = requireNonNull(writer, "writer");
        this.encoder = requireNonNull(encoder, "encoder");
        this.webSocketCloseHandler = requireNonNull(webSocketCloseHandler, "webSocketCloseHandler");
        this.expectMaskedFrames = expectMaskedFrames;
    }

    @Override
    public void process(StreamDecoderInput in, StreamDecoderOutput<WebSocketFrame> out) throws Exception {
        while (in.readableBytes() > 0) {
            // Discard all data received if closing handshake was received before.
            if (receivedClosingHandshake) {
                in.skipBytes(in.readableBytes());
                return;
            }

            switch (state) {
                case READING_FIRST:
                    if (in.readableBytes() == 0) {
                        return;
                    }

                    framePayloadLength = 0;

                    // FIN, RSV, OPCODE
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
                        protocolViolation("RSV != 0 and no extension negotiated, RSV:" + frameRsv);
                        return;
                    }

                    if (!allowMaskMismatch && expectMaskedFrames != frameMasked) {
                        protocolViolation("received a frame that is not masked as expected");
                        return;
                    }

                    if (frameOpcode > 7) { // control frame (have MSB in opcode set)

                        // control frames MUST NOT be fragmented
                        if (!finalFragment) {
                            protocolViolation("fragmented control frame");
                            return;
                        }

                        // control frames MUST have payload 125 octets or less
                        if (framePayloadLen1 > 125) {
                            protocolViolation("control frame with payload length > 125 octets");
                            return;
                        }

                        // check for reserved control frame opcodes
                        if (!(frameOpcode == WebSocketFrameType.CLOSE.opcode() ||
                              frameOpcode == WebSocketFrameType.PING.opcode() ||
                              frameOpcode == WebSocketFrameType.PONG.opcode())) {
                            protocolViolation("control frame using reserved opcode " + frameOpcode);
                            return;
                        }

                        // close frame : if there is a body, the first two bytes of the
                        // body MUST be a 2-byte unsigned integer representing a getStatus code
                        if (frameOpcode == 8 && framePayloadLen1 == 1) {
                            protocolViolation("received close control frame with payload len 1");
                            return;
                        }
                    } else { // data frame
                        // check for reserved data frame opcodes
                        if (!(frameOpcode == WebSocketFrameType.CONTINUATION.opcode() ||
                              frameOpcode == WebSocketFrameType.TEXT.opcode() ||
                              frameOpcode == WebSocketFrameType.BINARY.opcode())) {
                            protocolViolation("data frame using reserved opcode " + frameOpcode);
                            return;
                        }

                        if (fragmentedFramesCount == 0) {
                            if (frameOpcode == WebSocketFrameType.CONTINUATION.opcode()) {
                                protocolViolation("received continuation data frame " +
                                                  "outside fragmented message");
                            }
                        } else if (frameOpcode != WebSocketFrameType.CONTINUATION.opcode()) {
                            protocolViolation(
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
                            protocolViolation("invalid data frame length (not using minimal length encoding)");
                            return;
                        }
                    } else if (framePayloadLen1 == 127) {
                        if (in.readableBytes() < 8) {
                            return;
                        }
                        framePayloadLength = in.readLong();
                        if (framePayloadLength < 0) {
                            protocolViolation("invalid data frame length (negative length)");
                            return;
                        }

                        if (framePayloadLength < 65536) {
                            protocolViolation("invalid data frame length (not using minimal length encoding)");
                            return;
                        }
                    } else {
                        framePayloadLength = framePayloadLen1;
                    }

                    if (framePayloadLength > maxFramePayloadLength) {
                        protocolViolation(WebSocketCloseStatus.MESSAGE_TOO_BIG,
                                          "Max frame length of " + maxFramePayloadLength +
                                          " has been exceeded.");
                        return;
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
                        // Create the pong first just in case the payloadBuffer is released by the handler.
                        final ByteBuf encodedPong = encoder.encode(
                                ctx, WebSocketFrame.ofPooledPong(payloadBuffer.retainedDuplicate()));
                        out.add(WebSocketFrame.ofPooledPing(payloadBuffer));
                        final boolean ignored = writer.tryWrite(HttpData.wrap(encodedPong));
                        continue; // to while loop
                    }
                    assert payloadBuffer != null;
                    if (frameOpcode == WebSocketFrameType.PONG.opcode()) {
                        out.add(WebSocketFrame.ofPooledPong(payloadBuffer));
                        continue; // to while loop
                    }
                    if (frameOpcode == WebSocketFrameType.CLOSE.opcode()) {
                        receivedClosingHandshake = true;
                        final Channel channel = ctx.log()
                                                   .ensureAvailable(RequestLogProperty.SESSION)
                                                   .channel();
                        assert channel != null;
                        closeWebSocketInboundStream(channel);
                        checkCloseFrameBody(payloadBuffer);
                        out.add(WebSocketFrame.ofPooledClose(payloadBuffer));
                        webSocketCloseHandler.closeFrameReceived();
                        continue; // to while loop
                    }

                    // Return the frame
                    if (frameOpcode == WebSocketFrameType.TEXT.opcode()) {
                        out.add(WebSocketFrame.ofPooledText(payloadBuffer, finalFragment));
                    } else if (frameOpcode == WebSocketFrameType.BINARY.opcode()) {
                        out.add(WebSocketFrame.ofPooledBinary(payloadBuffer, finalFragment));
                    } else if (frameOpcode == WebSocketFrameType.CONTINUATION.opcode()) {
                        out.add(WebSocketFrame.ofPooledContinuation(payloadBuffer, finalFragment));
                    } else {
                        protocolViolation(WebSocketCloseStatus.INVALID_MESSAGE_TYPE,
                                          "Cannot decode web socket frame with opcode: " + frameOpcode);
                    }

                    if (finalFragment) {
                        fragmentedFramesCount = 0;
                    } else {
                        fragmentedFramesCount++;
                    }
                    continue; // to while loop
                case CORRUPT:
                    in.skipBytes(in.readableBytes());
                    return;
                default:
                    throw new Error("Shouldn't reach here.");
            }
        }
    }

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

    private void protocolViolation(String message) {
        protocolViolation(WebSocketCloseStatus.PROTOCOL_ERROR, message);
    }

    private void protocolViolation(WebSocketCloseStatus status, String message) {
        state = State.CORRUPT;
        throw new WebSocketProtocolViolationException(status, message);
    }

    private static int toFrameLength(long l) {
        // We know that the length is less or equal to Integer.MAX_VALUE.
        return (int) l;
    }

    private void checkCloseFrameBody(ByteBuf buffer) {
        try {
            if (buffer.readableBytes() < 2) {
                protocolViolation(WebSocketCloseStatus.INVALID_PAYLOAD_DATA, "Invalid close frame body");
            }

            // Must have 2 byte integer within the valid range
            final int statusCode = buffer.getShort(buffer.readerIndex());
            if (!WebSocketCloseStatus.isValidStatusCode(statusCode)) {
                protocolViolation("Invalid close frame status code: " + statusCode);
            }

            // May have UTF-8 message
            if (buffer.readableBytes() > 2) {
                try {
                    new Utf8Validator().check(buffer, buffer.readerIndex() + 2, buffer.readableBytes() - 2);
                } catch (IllegalArgumentException ex) {
                    protocolViolation(WebSocketCloseStatus.INVALID_PAYLOAD_DATA, "bytes are not UTF-8");
                }
            }
        } catch (Exception e) {
            buffer.release();
            throw e;
        }
    }

    @Override
    public void processOnError(Throwable cause) {
        // Do not send close if already sent
        if (webSocketCloseHandler.isCloseFrameSent()) {
            logger.warn("Unexpected exception while decoding a WebSocket.", cause);
            return;
        }
        final WebSocketCloseStatus closeStatus;
        if (cause instanceof WebSocketProtocolViolationException) {
            closeStatus = ((WebSocketProtocolViolationException) cause).closeStatus();
        } else {
            closeStatus = WebSocketCloseStatus.INTERNAL_SERVER_ERROR;
        }
        String reasonPhrase = cause.getMessage();
        if (reasonPhrase == null) {
            reasonPhrase = closeStatus.reasonPhrase();
        }
        final ByteBuf encoded = encoder.encode(ctx, WebSocketFrame.ofClose(closeStatus, reasonPhrase));
        if (writer.tryWrite(HttpData.wrap(encoded))) {
            webSocketCloseHandler.closeStreams(cause);
        }
    }
}
