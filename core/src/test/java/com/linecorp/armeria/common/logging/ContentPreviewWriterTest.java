/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import org.apache.commons.codec.binary.Hex;
import org.junit.Test;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.unsafe.ByteBufHttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class ContentPreviewWriterTest {

    private static List<ByteBuf> sliceBytes(byte[] bytes, int length) {
        final List<ByteBuf> buffers = new ArrayList<>();
        for (int i = 0; i < bytes.length; i += length) {
            buffers.add(Unpooled.wrappedBuffer(bytes, i, Math.min(bytes.length - i, length)));
        }
        return buffers;
    }

    private static Consumer<ByteBuf> plainText(ContentPreviewWriter writer, Charset charset) {
        return b -> {
            writer.write(HttpHeaders.of().contentType(MediaType.PLAIN_TEXT_UTF_8.withCharset(charset)),
                         new ByteBufHttpData(b, false));
        };
    }

    private static final String TEST_STR = "abcdefghijkmnopqrstuvwyxzABCDEFGHIJKMNOPQRSTUVWXYZ" +
                                           "가갸거겨고교구규그기가나다라마바사아자차카타파하";

    private static void testSlice(String str, Charset charset, int maxLength, int sliceLength) {
        final ContentPreviewWriter writer = ContentPreviewWriter.ofString(maxLength);
        final String expected = str.substring(0, Math.min(str.length(), maxLength));
        sliceBytes(str.getBytes(charset), sliceLength).forEach(plainText(writer, charset));
        assertThat(writer.produce()).isEqualTo(expected);
    }

    private static void testSliceBytes(byte[] bytes, int maxLength, int sliceLength) {
        ContentPreviewWriter writer = ContentPreviewWriter.ofByteBuf(maxLength, byteBuf -> {
            byte[] b = new byte[maxLength];
            byteBuf.readBytes(b, 0, Math.min(byteBuf.readableBytes(), maxLength));
            return Hex.encodeHexString(b);
        });
        sliceBytes(bytes, maxLength).forEach(plainText(writer, Charset.defaultCharset()));
        assertThat(writer.produce()).isEqualTo(Hex.encodeHexString(Arrays.copyOf(bytes, maxLength)));
    }

    @Test
    public void testAggreagted() {
        for (int sliceLength : new int[] {1,3,6,10}) {
            for (int maxLength : new int[] { 1, 3, 6, 10, 12, 15, 25, 35, 200 }) {
                testSlice(TEST_STR, StandardCharsets.UTF_8, maxLength, sliceLength);
                testSliceBytes(TEST_STR.getBytes(), maxLength, sliceLength);
            }
        }
    }
}
