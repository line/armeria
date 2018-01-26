/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.client.encoding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import com.linecorp.armeria.unsafe.ByteBufHttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

abstract class AbstractStreamDecoderTest {

    abstract StreamDecoder newDecoder();

    @Test
    public void decodedBufferShouldNotLeak() {
        final StreamDecoder decoder = newDecoder();
        final ByteBuf buf = Unpooled.buffer();
        decoder.decode(new ByteBufHttpData(buf, false));
        assertThat(buf.refCnt()).isZero();
    }
}
