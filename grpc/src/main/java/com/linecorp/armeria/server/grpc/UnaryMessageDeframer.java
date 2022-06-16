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

import java.util.List;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.protocol.AbstractMessageDeframer;
import com.linecorp.armeria.common.grpc.protocol.Decompressor;
import com.linecorp.armeria.common.grpc.protocol.DeframedMessage;
import com.linecorp.armeria.internal.common.grpc.protocol.Base64Decoder;
import com.linecorp.armeria.internal.common.stream.ByteBufsDecoderInput;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

final class UnaryMessageDeframer extends AbstractMessageDeframer {

    private final ByteBufAllocator alloc;
    @Nullable
    private final Base64Decoder base64Decoder;

    UnaryMessageDeframer(ByteBufAllocator alloc, int maxMessageLength, boolean grpcWebText) {
        super(maxMessageLength);
        this.alloc = alloc;
        if (grpcWebText) {
            base64Decoder = new Base64Decoder(alloc);
        } else {
            base64Decoder = null;
        }
    }

    DeframedMessage deframe(HttpData data) {
        ByteBuf buf = data.byteBuf();
        if (base64Decoder != null) {
            buf = base64Decoder.decode(buf);
        }

        try (UnaryDecoderInput input = new UnaryDecoderInput(buf)) {
            readHeader(input);
            return readBody(input);
        }
    }

    DeframedMessage deframe(List<HttpObject> objects) {
        try (ByteBufsDecoderInput input = new ByteBufsDecoderInput(alloc)) {
            for (HttpObject object : objects) {
                if (object instanceof HttpData) {
                    ByteBuf buf = ((HttpData) object).byteBuf();
                    if (base64Decoder != null) {
                        buf = base64Decoder.decode(buf);
                    }
                    input.add(buf);
                }
            }
            readHeader(input);
            return readBody(input);
        }
    }

    @Override
    protected UnaryMessageDeframer decompressor(@Nullable Decompressor decompressor) {
        return (UnaryMessageDeframer) super.decompressor(decompressor);
    }
}
