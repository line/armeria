/*
 * Copyright 2016 LINE Corporation
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

import com.google.common.base.MoreObjects;

/**
 * Default {@link HttpData} implementation.
 */
public final class DefaultHttpData extends AbstractHttpData {

    private final byte[] data;
    private final int offset;
    private final int length;
    private final boolean endOfStream;

    /**
     * Creates a new instance.
     */
    public DefaultHttpData(byte[] data, int offset, int length, boolean endOfStream) {
        this.data = data;
        this.offset = offset;
        this.length = length;
        this.endOfStream = endOfStream;
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
    @SuppressWarnings("ImplicitArrayToString")
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("offset", offset)
                          .add("length", length)
                          .add("array", data.toString()).toString();
    }

    @Override
    public boolean isEndOfStream() {
        return endOfStream;
    }

    @Override
    protected byte getByte(int index) {
        return data[offset + index];
    }
}
