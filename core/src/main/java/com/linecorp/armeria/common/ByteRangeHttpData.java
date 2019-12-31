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

package com.linecorp.armeria.common;

import java.util.Arrays;

/**
 * A {@link AbstractHttpData} that contains a slice into a backing array, to allow mutations to the array to
 * still be reflected here.
 */
class ByteRangeHttpData extends AbstractHttpData {

    private final byte[] array;
    private final int offset;
    private final int length;
    private final boolean endOfStream;

    ByteRangeHttpData(byte[] array, int offset, int length, boolean endOfStream) {
        this.array = array;
        this.offset = offset;
        this.length = length;
        this.endOfStream = endOfStream;
    }

    @Override
    protected byte getByte(int index) {
        return array[offset + index];
    }

    @Override
    public byte[] array() {
        return Arrays.copyOfRange(array, offset, offset + length);
    }

    @Override
    public int length() {
        return length;
    }

    @Override
    public boolean isEndOfStream() {
        return endOfStream;
    }

    @Override
    public ByteRangeHttpData withEndOfStream() {
        return new ByteRangeHttpData(array, offset, length, true);
    }
}
