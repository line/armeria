/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

public class Http1ResponseDecoderTest {

    @Test
    void testRequestTimeoutClosesImmediately() throws Exception {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final Http1ResponseDecoder decoder = new Http1ResponseDecoder(channel);
            channel.pipeline().addLast(decoder);

            final HttpHeaders httpHeaders = new DefaultHttpHeaders();
            httpHeaders.add(HttpHeaderNames.CONNECTION, "close");
            final DefaultHttpResponse response = new DefaultHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.REQUEST_TIMEOUT, httpHeaders);

            channel.writeOneInbound(response);
            assertThat(channel.isOpen()).isFalse();
        } finally {
            channel.close().await();
        }
    }
}
