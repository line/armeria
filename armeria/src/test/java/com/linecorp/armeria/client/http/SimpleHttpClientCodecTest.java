/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.client.http;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.linecorp.armeria.client.ClientCodec.EncodeResult;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.ServiceInvocationContext;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

public class SimpleHttpClientCodecTest {

    private static final Scheme SCHEME = Scheme.parse("none+http");
    private static final Method EXECUTE_METHOD;
    static {
        try {
            EXECUTE_METHOD = SimpleHttpClient.class.getMethod("execute", SimpleHttpRequest.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Did you rename the execute method?");
        }
    }

    @Rule
    public MockitoRule mocks = MockitoJUnit.rule();

    @Mock
    private Channel channel;

    @Mock
    private ServiceInvocationContext context;

    private SimpleHttpClientCodec codec;

    @Before
    public void setUp() {
        codec = new SimpleHttpClientCodec("www.github.com");
        when(channel.alloc()).thenReturn(ByteBufAllocator.DEFAULT);
    }

    @Test
    public void encodeRequestNoBody() {
        SimpleHttpRequest request = SimpleHttpRequestBuilder.forGet("/foo?q=foo&bar=baz")
                .header(HttpHeaderNames.ORIGIN, "localhost")
                .build();
        EncodeResult result = codec.encodeRequest(channel, SCHEME.sessionProtocol(), EXECUTE_METHOD, new Object[]{ request });
        assertTrue(result.isSuccess());
        assertEquals(SCHEME, result.encodedScheme().get());
        assertEquals("/foo", result.encodedPath().get());
        assertEquals("www.github.com", result.encodedHost().get());
        FullHttpRequest fullHttpRequest = (FullHttpRequest) result.content();
        assertEquals(HttpVersion.HTTP_1_1, fullHttpRequest.protocolVersion());
        assertEquals("/foo?q=foo&bar=baz", fullHttpRequest.uri());
        assertEquals(HttpMethod.GET, fullHttpRequest.method());
        assertEquals("localhost", fullHttpRequest.headers().get(HttpHeaderNames.ORIGIN));
    }

    @Test
    public void encodeRequestWithBody() {
        SimpleHttpRequest request = SimpleHttpRequestBuilder.forGet("/foo?q=foo&bar=baz")
                .content("lorem ipsum foo bar", StandardCharsets.UTF_8)
                .header(HttpHeaderNames.ORIGIN, "localhost")
                .build();
        EncodeResult result = codec.encodeRequest(channel, SCHEME.sessionProtocol(), EXECUTE_METHOD, new Object[]{ request });
        assertTrue(result.isSuccess());
        assertEquals(SCHEME, result.encodedScheme().get());
        assertEquals("/foo", result.encodedPath().get());
        assertEquals("www.github.com", result.encodedHost().get());
        FullHttpRequest fullHttpRequest = (FullHttpRequest) result.content();
        assertEquals(HttpVersion.HTTP_1_1, fullHttpRequest.protocolVersion());
        assertEquals("/foo?q=foo&bar=baz", fullHttpRequest.uri());
        assertEquals(HttpMethod.GET, fullHttpRequest.method());
        assertEquals("localhost", fullHttpRequest.headers().get(HttpHeaderNames.ORIGIN));
        assertEquals("lorem ipsum foo bar",
                     new String(ByteBufUtil.getBytes(fullHttpRequest.content()), StandardCharsets.UTF_8));
    }

    @Test
    public void decodeResponseNoBody() throws Exception {
        FullHttpResponse fullHttpResponse =
                new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        fullHttpResponse.headers().set(HttpHeaderNames.CACHE_CONTROL, "nevercache");
        SimpleHttpResponse response = codec.decodeResponse(context, Unpooled.buffer(0), fullHttpResponse);
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals("nevercache", response.headers().get(HttpHeaderNames.CACHE_CONTROL));
        assertEquals(0, response.content().length);
    }

    @Test
    public void decodeResponseWithBody() throws Exception {
        FullHttpResponse fullHttpResponse =
                new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        fullHttpResponse.headers().set(HttpHeaderNames.CACHE_CONTROL, "nevercache");
        fullHttpResponse.content().writeBytes("response content".getBytes(StandardCharsets.UTF_8));
        SimpleHttpResponse response = codec.decodeResponse(context, fullHttpResponse.content(),
                                                           fullHttpResponse);
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals("nevercache", response.headers().get(HttpHeaderNames.CACHE_CONTROL));
        assertArrayEquals("response content".getBytes(StandardCharsets.UTF_8),
                          response.content());
    }
}
