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
import java.util.List;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.stream.NoopSubscriber;
import com.linecorp.armeria.unsafe.common.ByteBufHttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.concurrent.ImmediateEventExecutor;

class HttpEncodedResponseTest {

    @Test
    void testLeak() {
        final ByteBuf buf = Unpooled.buffer();
        buf.writeCharSequence("foo", StandardCharsets.UTF_8);

        final HttpResponse orig =
                AggregatedHttpResponse.of(HttpStatus.OK,
                                          MediaType.PLAIN_TEXT_UTF_8,
                                          new ByteBufHttpData(buf, true)).toHttpResponse();
        final HttpEncodedResponse encoded = new HttpEncodedResponse(
                orig, HttpEncodingType.DEFLATE, mediaType -> true, 1);

        // Drain the stream.
        encoded.subscribe(NoopSubscriber.get(), ImmediateEventExecutor.INSTANCE);

        // 'buf' should be released.
        assertThat(buf.refCnt()).isZero();
    }

    @Test
    void doNotEncodeWhenContentShouldBeEmpty() {
        final ResponseHeaders headers = ResponseHeaders.builder(HttpStatus.NO_CONTENT).contentType(
                MediaType.PLAIN_TEXT_UTF_8).build();
        // Add CONTINUE not to validate when creating HttpResponse.
        final HttpResponse orig = HttpResponse.of(ResponseHeaders.of(HttpStatus.CONTINUE), headers,
                                                  HttpData.ofUtf8("foo"));
        final HttpEncodedResponse encoded = new HttpEncodedResponse(
                orig, HttpEncodingType.DEFLATE, mediaType -> true, 1);
        final List<HttpObject> join = encoded.drainAll().join();
        assertThat(((HttpData) join.get(2)).toStringUtf8()).isEqualTo("foo");
    }
}
