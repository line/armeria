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
package com.linecorp.armeria.server.encoding;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.stream.NoopSubscriber;
import com.linecorp.armeria.internal.ByteBufHttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.concurrent.ImmediateEventExecutor;

public class HttpEncodedResponseTest {

    @Test
    public void testLeak() {
        final ByteBuf buf = Unpooled.buffer();
        buf.writeCharSequence("foo", StandardCharsets.UTF_8);

        final HttpResponse orig = HttpResponse.of(
                AggregatedHttpMessage.of(HttpStatus.OK,
                                         MediaType.PLAIN_TEXT_UTF_8,
                                         new ByteBufHttpData(buf, true)));
        final HttpEncodedResponse encoded = new HttpEncodedResponse(
                orig, HttpEncodingType.DEFLATE, mediaType -> true, 1);

        // Drain the stream.
        encoded.subscribe(NoopSubscriber.get(), ImmediateEventExecutor.INSTANCE);

        // 'buf' should be released.
        assertThat(buf.refCnt()).isZero();
    }
}
