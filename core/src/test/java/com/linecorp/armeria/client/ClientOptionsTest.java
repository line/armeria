/*
 * Copyright 2015 LINE Corporation
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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.util.Optional;

import org.junit.Test;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;

import io.netty.util.AsciiString;

public class ClientOptionsTest {

    @Test
    public void testSetHttpHeader() {
        HttpHeaders httpHeader = HttpHeaders.of(AsciiString.of("x-user-defined"), "HEADER_VALUE");

        ClientOptions options = ClientOptions.of(ClientOption.HTTP_HEADERS.newValue(httpHeader));
        assertThat(options.get(ClientOption.HTTP_HEADERS), is(Optional.of(httpHeader)));

        ClientOptions options2 = ClientOptions.DEFAULT;
        assertThat(options2.get(ClientOption.HTTP_HEADERS), is(Optional.of(HttpHeaders.EMPTY_HEADERS)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetBlackListHeader() {
        ClientOptions.of(ClientOption.HTTP_HEADERS.newValue(
                HttpHeaders.of(HttpHeaderNames.HOST, "localhost")));
    }

    @Test(expected = NullPointerException.class)
    public void testInvalidDefaultWriteTimeoutMillis() {
        ClientOptions.of(ClientOption.DEFAULT_WRITE_TIMEOUT_MILLIS.newValue(null));
    }

    @Test(expected = NullPointerException.class)
    public void testInvalidDefaultResponseTimeoutMillis() {
        ClientOptions.of(ClientOption.DEFAULT_RESPONSE_TIMEOUT_MILLIS.newValue(null));
    }

    @Test(expected = NullPointerException.class)
    public void testInvalidDefaultMaxResponseLength() {
        ClientOptions.of(ClientOption.DEFAULT_MAX_RESPONSE_LENGTH.newValue(null));
    }
}
