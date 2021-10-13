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
package com.linecorp.armeria.internal.common;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.util.IllegalReferenceCountException;

public final class ByteArrayUtil {

    private static final byte[] SAFE_OCTETS = new byte[256];

    static {
        final String safeOctetStr = "`~!@#$%^&*()-_=+\t[{]}\\|;:'\",<.>/?" +
                                    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int i = 0; i < safeOctetStr.length(); i++) {
            SAFE_OCTETS[safeOctetStr.charAt(i)] = -1;
        }
    }

    public static StringBuilder appendPreviews(StringBuilder buf, byte[] array, int offset, int previewLength) {
        // Append the hex preview if contains non-ASCII chars.
        final int endOffset = offset + previewLength;
        for (int i = offset; i < endOffset; i++) {
            if (SAFE_OCTETS[array[i] & 0xFF] == 0) {
                return buf.append("hex=").append(ByteBufUtil.hexDump(array, offset, previewLength));
            }
        }

        // Append the text preview otherwise.
        return buf.append("text=").append(new String(array, 0, offset, previewLength));
    }

    /**
     * Generates preview and add it to the specified {@link StringBuilder}.
     * This returns non-null {@code byte[]} if the specified {@code array} is not null, or the specified length
     * is less than or equal to {@code 16}.
     */
    @Nullable
    public static byte[] generatePreview(StringBuilder strBuf, @Nullable byte[] array, ByteBuf buf,
                                         int length, boolean hint) {
        // Generate the preview array.
        final int previewLength = Math.min(16, length);
        final int offset;
        boolean returnArray = false;
        if (array == null) {
            try {
                if (buf.hasArray()) {
                    array = buf.array();
                    offset = buf.arrayOffset() + buf.readerIndex();
                } else if (!hint) {
                    array = ByteBufUtil.getBytes(buf, buf.readerIndex(), previewLength);
                    offset = 0;
                    returnArray = true;
                } else {
                    // Can't call getBytes() when generating the hint string
                    // because it will also create a leak record.
                    strBuf.append("<unknown>");
                    return null;
                }
            } catch (IllegalReferenceCountException e) {
                // Shouldn't really happen when used ByteBuf correctly,
                // but we just don't make toString() fail because of this.
                strBuf.append("badRefCnt");
                return null;
            }
        } else {
            offset = 0;
            returnArray = true;
        }

        appendPreviews(strBuf, array, offset, previewLength);
        return returnArray ? array : null;
    }

    private ByteArrayUtil() {}
}
