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
/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
// (BSD License: http://www.opensource.org/licenses/bsd-license)
//
// Copyright (c) 2011, Joe Walnes and contributors
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or
// without modification, are permitted provided that the
// following conditions are met:
//
// * Redistributions of source code must retain the above
// copyright notice, this list of conditions and the
// following disclaimer.
//
// * Redistributions in binary form must reproduce the above
// copyright notice, this list of conditions and the
// following disclaimer in the documentation and/or other
// materials provided with the distribution.
//
// * Neither the name of the Webbit nor the names of
// its contributors may be used to endorse or promote products
// derived from this software without specific prior written
// permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
// CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
// INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
// MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
// CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
// INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
// (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
// GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
// BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
// LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
// OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
// POSSIBILITY OF SUCH DAMAGE.

package com.linecorp.armeria.internal.common.websocket;

import static com.linecorp.armeria.internal.common.websocket.WebSocketUtil.intMask;

import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.ByteBufAccessMode;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketFrameType;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.TooLongFrameException;

/**
 * <p>
 * Encodes a web socket frame into wire protocol version 8 format. This code was forked from <a
 * href="https://github.com/joewalnes/webbit">webbit</a> and modified.
 * </p>
 */
public final class WebSocketFrameEncoder {

    // Forked from Netty 4.1.69 at 34a31522f0145e2d434aaea2ef8ac5ed8d1a91a0

    private static final Logger logger = LoggerFactory.getLogger(WebSocketFrameEncoder.class);

    /**
     * The size threshold for gathering writes. Non-Masked messages bigger than this size will be be sent
     * fragmented as a header and a content ByteBuf whereas messages smaller than the size will be merged
     * into a single buffer and sent at once.<br>
     * Masked messages will always be sent at once.
     */
    private static final int GATHERING_WRITE_THRESHOLD = 1024;

    private static final WebSocketFrameEncoder serverEncoder = new WebSocketFrameEncoder(false);
    private static final WebSocketFrameEncoder clientEncoder = new WebSocketFrameEncoder(true);

    public static WebSocketFrameEncoder of(boolean maskPayload) {
        if (maskPayload) {
            return clientEncoder;
        } else {
            return serverEncoder;
        }
    }

    private final boolean maskPayload;

    private WebSocketFrameEncoder(boolean maskPayload) {
        this.maskPayload = maskPayload;
    }

    public ByteBuf encode(RequestContext ctx, WebSocketFrame msg) {
        try (WebSocketFrame msg0 = msg) {
            return encode0(ctx, msg0);
        }
    }

    private ByteBuf encode0(RequestContext ctx, WebSocketFrame msg) {
        final WebSocketFrameType type = msg.type();
        final int length = msg.dataLength();
        if (type == WebSocketFrameType.PING && length > 125) {
            throw new TooLongFrameException("invalid payload for PING (payload length must be <= 125, was " +
                                            length);
        }

        logger.trace("Encoding WebSocket Frame opCode={} length={}", type.opcode(), length);

        // https://datatracker.ietf.org/doc/html/rfc6455#section-5.2
        int b0 = 0;
        if (msg.isFinalFragment()) {
            b0 |= 1 << 7;
        }
        // b0 |= msg.rsv() << 4; // TODO(minwoox): Support rsv
        b0 |= type.opcode();

        boolean release = true;
        ByteBuf buf = null;
        try {
            final int maskLength = maskPayload ? 4 : 0;
            if (length <= 125) {
                final int size = 2 + maskLength + length;
                buf = ctx.alloc().buffer(size);
                buf.writeByte(b0);
                // maskPayload + payload length (<= 125)
                final byte b = (byte) (maskPayload ? 0x80 | (byte) length : (byte) length);
                buf.writeByte(b);
            } else if (length <= 0xFFFF) {
                int size = 4 + maskLength;
                if (maskPayload || length <= GATHERING_WRITE_THRESHOLD) {
                    size += length;
                }
                buf = ctx.alloc().buffer(size);
                buf.writeByte(b0);
                // maskPayload + payload length (== 126). When payload length is 126, following two bytes
                // are the payload length.
                buf.writeByte(maskPayload ? 0xFE : 126); // 11111110 : 01111110
                buf.writeByte(length >>> 8 & 0xFF);
                buf.writeByte(length & 0xFF);
            } else {
                int size = 10 + maskLength;
                if (maskPayload) {
                    size += length;
                }
                buf = ctx.alloc().buffer(size);
                buf.writeByte(b0);
                // maskPayload + payload length (== 127). When payload length is 127, following 8 bytes
                // are the payload length.
                buf.writeByte(maskPayload ? 0xFF : 127);
                buf.writeLong(length);
            }

            // Write payload
            if (maskPayload) {
                final int random = (int) (Math.random() * Integer.MAX_VALUE);
                final byte[] mask = ByteBuffer.allocate(4).putInt(random).array();
                buf.writeBytes(mask);

                final int intMask = intMask(mask);
                int counter = 0;
                final ByteBuf data = msg.byteBuf(ByteBufAccessMode.DUPLICATE);
                int i = data.readerIndex();
                final int end = data.writerIndex();

                for (; i + 3 < end; i += 4) {
                    final int intData = data.getInt(i);
                    buf.writeInt(intData ^ intMask);
                }
                for (; i < end; i++) {
                    final byte byteData = data.getByte(i);
                    buf.writeByte(byteData ^ mask[counter++ % 4]);
                }
            } else {
                if (buf.writableBytes() >= msg.dataLength()) {
                    // merge buffers as this is cheaper then a gathering write if the payload is small enough
                    buf.writeBytes(msg.byteBuf(ByteBufAccessMode.DUPLICATE));
                } else {
                    buf = Unpooled.wrappedBuffer(buf, msg.byteBuf(ByteBufAccessMode.FOR_IO));
                }
            }
            release = false;
            return buf;
        } finally {
            if (release && buf != null) {
                buf.release();
            }
        }
    }
}
