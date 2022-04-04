/*
 *  Copyright 2022 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.server.grpc;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.protocol.AbstractMessageDeframer;
import com.linecorp.armeria.common.grpc.protocol.Decompressor;
import com.linecorp.armeria.common.grpc.protocol.DeframedMessage;
import com.linecorp.armeria.internal.common.grpc.protocol.Base64Decoder;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

class UnaryMessageDeframer extends AbstractMessageDeframer {

    private final ByteBufAllocator alloc;
    private final boolean grpcWebText;

    UnaryMessageDeframer(ByteBufAllocator alloc, int maxMessageLength, boolean grpcWebText) {
        super(maxMessageLength);
        this.alloc = alloc;
        this.grpcWebText = grpcWebText;
    }

    // Valid type is always positive.

    DeframedMessage deframe(HttpData data) {
        ByteBuf buf = data.byteBuf();
        if (grpcWebText) {
            buf = new Base64Decoder(alloc).decode(buf);
        }

        try (UnaryDecoderInput input = new UnaryDecoderInput(buf)) {
            readHeader(input);
            return readBody(input);
        }
    }

    @Override
    protected UnaryMessageDeframer decompressor(@Nullable Decompressor decompressor) {
        return (UnaryMessageDeframer) super.decompressor(decompressor);
    }
}
