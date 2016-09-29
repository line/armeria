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

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;

public class SimpleHttpResponseTest {

    @Test
    public void normal() {
        HttpHeaders headers = new DefaultHttpHeaders();
        headers.set(HttpHeaderNames.ACCEPT, "utf-8");
        byte[] body = "content".getBytes(StandardCharsets.UTF_8);
        SimpleHttpResponse response = new SimpleHttpResponse(HttpResponseStatus.NOT_FOUND, headers, body);
        assertEquals(HttpResponseStatus.NOT_FOUND, response.status());
        assertEquals(headers, response.headers());
        assertArrayEquals(body, response.content());
    }
}
