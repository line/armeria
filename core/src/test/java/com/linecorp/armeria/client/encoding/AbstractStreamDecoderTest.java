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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import org.junit.Test;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.unsafe.ByteBufHttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufHolder;

abstract class AbstractStreamDecoderTest {

    private static final byte[] PAYLOAD;

    static {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DeflaterOutputStream zip = new DeflaterOutputStream(bos, new Deflater(-1, true))) {
            zip.write(new byte[] { 1, 2, 3, 4, 5 });
        } catch (IOException e) {
            throw new Error(e);
        }
        PAYLOAD = bos.toByteArray();
    }

    abstract StreamDecoder newDecoder();

    @Test
    public void notEmpty() {
        final StreamDecoder decoder = newDecoder();
        final ByteBuf buf = ByteBufAllocator.DEFAULT.buffer();
        buf.writeBytes(PAYLOAD);
        final HttpData data = decoder.decode(new ByteBufHttpData(buf, false));
        assertThat(buf.refCnt()).isZero();
        assertThat(data).isInstanceOfSatisfying(ByteBufHolder.class, d -> assertThat(d.refCnt()).isEqualTo(1));
        ((ByteBufHolder) data).release();
    }

    @Test
    public void empty_unpooled() {
        final StreamDecoder decoder = newDecoder();
        final HttpData data = decoder.decode(HttpData.EMPTY_DATA);
        assertThat(data).isNotInstanceOf(ByteBufHolder.class);
    }

    @Test
    public void empty_pooled() {
        final StreamDecoder decoder = newDecoder();
        final ByteBuf buf = ByteBufAllocator.DEFAULT.buffer();
        final HttpData data = decoder.decode(new ByteBufHttpData(buf, false));
        assertThat(buf.refCnt()).isZero();

        // Even for a pooled empty input, the result is unpooled since there's no point in pooling empty
        // buffers.
        assertThat(data).isNotInstanceOf(ByteBufHolder.class);
    }
}
