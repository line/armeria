/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.http;

import java.util.Arrays;

import com.google.common.base.MoreObjects;

final class DefaultHttpData implements HttpData {

    static final HttpData EMPTY_DATA = new DefaultHttpData(new byte[0], 0, 0);

    private final byte[] data;
    private final int offset;
    private final int length;

    DefaultHttpData(byte[] data, int offset, int length) {
        this.data = data;
        this.offset = offset;
        this.length = length;
    }

    @Override
    public byte[] array() {
        return data;
    }

    @Override
    public int offset() {
        return offset;
    }

    @Override
    public int length() {
        return length;
    }

    @Override
    public int hashCode() {
        final int end = offset + length;
        int hash = 1;
        for (int i = offset; i < end; i++) {
            hash = hash * 31 + data[i];
        }
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof HttpData)) {
            return false;
        }

        if (this == obj) {
            return true;
        }

        final HttpData that = (HttpData) obj;
        if (length() != that.length()) {
            return false;
        }

        final int endOffset = offset + length;
        for (int i = offset, j = that.offset(); i < endOffset; i++, j++) {
            if (data[i] != data[j]) {
                return false;
            }
        }

        return true;
    }

    @Override
    @SuppressWarnings("ImplicitArrayToString")
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("offset", offset)
                          .add("length", length)
                          .add("array", data.toString()).toString();
    }
}
