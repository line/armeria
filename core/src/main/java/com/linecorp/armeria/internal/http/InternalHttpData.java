/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.internal.http;

import com.linecorp.armeria.common.http.HttpData;

/**
 * Internal APIs of {@link HttpData}. Should not be used in user code.
 */
public interface InternalHttpData extends HttpData {

    /**
     * Gets the {@link byte} value at the given {@code index} relative to the {@link HttpData}'s
     * {@link #offset()}.
     */
    byte getByte(int index);

    default boolean equalTo(Object obj) {
        if (!(obj instanceof InternalHttpData)) {
            return false;
        }

        if (this == obj) {
            return true;
        }

        final InternalHttpData that = (InternalHttpData) obj;
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
