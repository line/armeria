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

package com.linecorp.armeria.internal.common.grpc.protocol;

import java.util.function.Function;

import com.linecorp.armeria.common.HttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

public final class Base64DecoderUtil {

    public static Function<? super HttpData, ? extends ByteBuf> byteBufConverter(ByteBufAllocator alloc,
                                                                                 boolean decodeBase64) {
        if (decodeBase64) {
            final Base64Decoder base64Decoder = new Base64Decoder(alloc);
            return data -> base64Decoder.decode(data.byteBuf());
        } else {
            return HttpData::byteBuf;
        }
    }

    private Base64DecoderUtil() {}
}
