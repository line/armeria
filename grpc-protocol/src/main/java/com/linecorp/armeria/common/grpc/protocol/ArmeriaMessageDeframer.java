/*
 * Copyright 2020 LINE Corporation
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
 * Copyright 2014, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.linecorp.armeria.common.grpc.protocol;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.stream.HttpDecoder;
import com.linecorp.armeria.common.stream.StreamDecoderInput;
import com.linecorp.armeria.common.stream.StreamDecoderOutput;
import com.linecorp.armeria.internal.common.grpc.protocol.Base64Decoder;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/**
 * A deframer of messages transported in the gRPC wire format. See
 * <a href="https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md">gRPC Wire Format</a>
 * for more detail on the protocol.
 *
 * <p>The logic has been mostly copied from {@code io.grpc.internal.MessageDeframer}, while removing the buffer
 * abstraction in favor of using {@link ByteBuf} directly, and allowing the delivery of uncompressed frames as
 * a {@link ByteBuf} to optimize message parsing.
 */
@UnstableApi
public class ArmeriaMessageDeframer extends AbstractMessageDeframer implements HttpDecoder<DeframedMessage> {

    @Nullable
    private final Base64Decoder base64Decoder;

    private boolean startedDeframing;

    /**
     * Construct an {@link ArmeriaMessageDeframer} for reading messages out of a gRPC request or
     * response.
     */
    public ArmeriaMessageDeframer(int maxMessageLength) {
        super(maxMessageLength);
        base64Decoder = null;
    }

    /**
     * Construct an {@link ArmeriaMessageDeframer} for reading messages out of a gRPC request or
     * response with the specified parameters.
     */
    public ArmeriaMessageDeframer(int maxMessageLength, ByteBufAllocator alloc, boolean grpcWebText) {
        super(maxMessageLength);
        requireNonNull(alloc, "alloc");
        if (grpcWebText) {
            base64Decoder = new Base64Decoder(alloc);
        } else {
            base64Decoder = null;
        }
    }

    @Override
    public ByteBuf toByteBuf(HttpData in) {
        if (base64Decoder != null) {
            return base64Decoder.decode(in.byteBuf());
        } else {
            return in.byteBuf();
        }
    }

    @Override
    public void process(StreamDecoderInput in, StreamDecoderOutput<DeframedMessage> out) throws Exception {
        startedDeframing = true;
        int readableBytes = in.readableBytes();
        while (readableBytes >= requiredLength()) {
            final int length = requiredLength();
            if (isUninitializedType()) {
                readHeader(in);
            } else {
                out.add(readBody(in));
            }
            readableBytes -= length;
        }
    }

    @Override
    public ArmeriaMessageDeframer decompressor(@Nullable Decompressor decompressor) {
        checkState(!startedDeframing,
                   "Deframing has already started, cannot change decompressor mid-stream.");
        return (ArmeriaMessageDeframer) super.decompressor(decompressor);
    }
}
