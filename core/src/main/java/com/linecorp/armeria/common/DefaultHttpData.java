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
final class DefaultHttpData extends AbstractHttpData {

    static final HttpData EMPTY = new DefaultHttpData(new byte[0], false);

    private final byte[] data;
    private final boolean endOfStream;

    DefaultHttpData(byte[] data, boolean endOfStream) {
        this.data = data;
        this.endOfStream = endOfStream;
    }

    @Override
    public byte[] array() {
        return data;
    }

    @Override
    public int length() {
        return data.length;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("length", data.length)
                          .toString();
    }

    @Override
    public boolean isEndOfStream() {
        return endOfStream;
    }

    @Override
    protected byte getByte(int index) {
        return data[index];
    }

    @Override
    public DefaultHttpData withEndOfStream() {
        return new DefaultHttpData(data, true);
    }
}
