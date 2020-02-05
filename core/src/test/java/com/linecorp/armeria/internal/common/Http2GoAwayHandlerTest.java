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
package com.linecorp.armeria.internal.common;

import static com.linecorp.armeria.internal.common.Http2GoAwayHandler.isExpected;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http2.Http2Error;

class Http2GoAwayHandlerTest {
    @Test
    void testIsExpected() {
        final ByteBuf errorFlushing = Unpooled.copiedBuffer("Error flushing", StandardCharsets.UTF_8);
        final ByteBuf errorFlushing2 = Unpooled.copiedBuffer("Error flushing stream", StandardCharsets.UTF_8);
        final ByteBuf other = Unpooled.copiedBuffer("Other reasons", StandardCharsets.UTF_8);
        assertThat(isExpected(Http2Error.INTERNAL_ERROR.code(), errorFlushing)).isTrue();
        assertThat(isExpected(Http2Error.PROTOCOL_ERROR.code(), errorFlushing)).isFalse();
        assertThat(isExpected(Http2Error.INTERNAL_ERROR.code(), errorFlushing2)).isTrue();
        assertThat(isExpected(Http2Error.INTERNAL_ERROR.code(), other)).isFalse();
    }
}
