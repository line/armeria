/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.spring.web.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.core.io.buffer.DataBuffer;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpObject;

final class TestUtil {

    static void ensureHttpDataOfString(HttpObject httpObject, String expected) {
        assertThat(httpObject).isInstanceOf(HttpData.class);
        assertThat(((HttpData) httpObject).toStringUtf8()).isEqualTo(expected);
    }

    static String bufferToString(DataBuffer buffer) {
        final byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        return new String(bytes);
    }

    private TestUtil() {}
}
