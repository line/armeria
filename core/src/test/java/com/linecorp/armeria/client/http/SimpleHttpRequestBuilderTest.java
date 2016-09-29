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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.Test;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;

public class SimpleHttpRequestBuilderTest {

    @Test
    public void defaults() {
        SimpleHttpRequest request = SimpleHttpRequestBuilder.forGet("/path").build();
        assertEquals("/path", request.uri().toString());
        assertEquals(HttpMethod.GET, request.method());
        assertTrue(request.headers().isEmpty());
        assertEquals(0, request.content().length);
    }

    @Test
    public void httpMethods() {
        assertEquals(HttpMethod.GET, SimpleHttpRequestBuilder.forGet("/path").build().method());
        assertEquals(HttpMethod.POST, SimpleHttpRequestBuilder.forPost("/path").build().method());
        assertEquals(HttpMethod.PUT, SimpleHttpRequestBuilder.forPut("/path").build().method());
        assertEquals(HttpMethod.PATCH, SimpleHttpRequestBuilder.forPatch("/path").build().method());
        assertEquals(HttpMethod.DELETE, SimpleHttpRequestBuilder.forDelete("/path").build().method());
        assertEquals(HttpMethod.HEAD, SimpleHttpRequestBuilder.forHead("/path").build().method());
        assertEquals(HttpMethod.OPTIONS, SimpleHttpRequestBuilder.forOptions("/path").build().method());
    }

    @Test
    public void headerByName() {
        SimpleHttpRequest request = SimpleHttpRequestBuilder.forGet("/path")
                .header(HttpHeaderNames.ACCEPT, "utf-8")
                .header(HttpHeaderNames.COOKIE, "monster")
                .header(HttpHeaderNames.ACCEPT, "shift-jis")
                .build();
        assertEquals(3, request.headers().size());
        assertEquals(Arrays.asList("utf-8", "shift-jis"), request.headers().getAll(HttpHeaderNames.ACCEPT));
        assertEquals("monster", request.headers().get(HttpHeaderNames.COOKIE));
    }

    @Test
    public void headersObject() {
        HttpHeaders headers = new DefaultHttpHeaders();
        headers.set(HttpHeaderNames.CACHE_CONTROL, "alwayscache");
        headers.set(HttpHeaderNames.ACCEPT_ENCODING, "gzip");
        SimpleHttpRequest request = SimpleHttpRequestBuilder.forGet("/path")
                .header(HttpHeaderNames.ACCEPT, "utf-8")
                .headers(headers)
                .header(HttpHeaderNames.ORIGIN, "localhost")
                .build();
        assertEquals(4, request.headers().size());
        assertEquals("alwayscache", request.headers().get(HttpHeaderNames.CACHE_CONTROL));
        assertEquals("gzip", request.headers().get(HttpHeaderNames.ACCEPT_ENCODING));
        assertEquals("localhost", request.headers().get(HttpHeaderNames.ORIGIN));
        assertEquals("utf-8", request.headers().get(HttpHeaderNames.ACCEPT));
    }

    @Test
    public void bodyBytes() {
        byte[] contentBytes = "contentBytesWithSome日本語".getBytes(StandardCharsets.UTF_8);
        SimpleHttpRequest request = SimpleHttpRequestBuilder.forGet("/path")
                .content(contentBytes)
                .build();
        assertEquals(contentBytes.length, request.contentLength());
        assertArrayEquals(contentBytes, request.content());
    }

    @Test
    public void bodyString() {
        SimpleHttpRequest request = SimpleHttpRequestBuilder.forGet("/path")
                .content("contentWithSome日本語", StandardCharsets.UTF_8)
                .build();
        assertEquals("contentWithSome日本語",
                     new String(request.content(), StandardCharsets.UTF_8));
    }
}
