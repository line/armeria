/*
 * Copyright 2022 LINE Corporation
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

import com.linecorp.armeria.internal.common.ByteArrayBytes;

final class ByteArrayHttpData extends ByteArrayBytes implements HttpData {

    private static final byte[] EMPTY_BYTES = {};

    static final ByteArrayHttpData EMPTY = new ByteArrayHttpData(EMPTY_BYTES);
    static final ByteArrayHttpData EMPTY_EOS = new ByteArrayHttpData(EMPTY_BYTES, true);

    private final boolean endOfStream;

    /**
     * Creates a new instance.
     */
    ByteArrayHttpData(byte[] array) {
        super(array);
        endOfStream = false;
    }

    private ByteArrayHttpData(byte[] array, boolean endOfStream) {
        super(array);
        this.endOfStream = endOfStream;
    }

    @Override
    public HttpData withEndOfStream(boolean endOfStream) {
        if (this.endOfStream == endOfStream) {
            return this;
        }
        if (isEmpty()) {
            return endOfStream ? EMPTY_EOS : EMPTY;
        }

        return new ByteArrayHttpData(array(), endOfStream);
    }

    @Override
    public boolean isEndOfStream() {
        return endOfStream;
    }

    @SuppressWarnings("RedundantMethodOverride")
    @Override
    public int hashCode() {
        // Use hashcode in ByteBufBytes because we don't use endOfStream in equals.
        return super.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HttpData)) {
            return false;
        }

        final HttpData that = (HttpData) o;
        if (length() != that.length()) {
            return false;
        }

        return Arrays.equals(array(), that.array());
    }

    @Override
    public String toString() {
        final String toString = super.toString();
        if (!isEndOfStream()) {
            return toString;
        }
        return toString + ", {EOS}";
    }
}
