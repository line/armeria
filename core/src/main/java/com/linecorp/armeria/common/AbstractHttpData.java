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

package com.linecorp.armeria.common;

/**
 * Support APIs for creating well-behaved {@link HttpData} objects. {@link HttpData} generally should extend
 * {@link AbstractHttpData} to interact with other {@link HttpData} implementations, via, e.g., {@code equals}.
 */
public abstract class AbstractHttpData implements HttpData {

    /**
     * Gets the {@link byte} value at the given {@code index} relative to the {@link HttpData}'s
     * {@link #offset()}.
     */
    protected abstract byte getByte(int index);

    @Override
    public int hashCode() {
        int hash = 1;
        for (int i = 0; i < length(); i++) {
            hash = hash * 31 + getByte(i);
        }
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof AbstractHttpData)) {
            return false;
        }

        if (this == obj) {
            return true;
        }

        final AbstractHttpData that = (AbstractHttpData) obj;
        if (length() != that.length()) {
            return false;
        }

        for (int i = 0; i < length(); i++) {
            if (getByte(i) != that.getByte(i)) {
                return false;
            }
        }

        return true;
    }
}
