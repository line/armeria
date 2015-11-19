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
package com.linecorp.armeria.client;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.util.Optional;

import org.junit.Test;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;

public class ClientOptionsTest {

    @Test
    public void testDefaultOptions() {
        ClientOptions config = ClientOptions.DEFAULT;

        assertThat(config.responseTimeoutPolicy(), is(not(nullValue())));
        assertThat(config.writeTimeoutPolicy(), is(not(nullValue())));
    }

    @Test
    public void testSetHttpHeader() {
        HttpHeaders httpHeader = new DefaultHttpHeaders();
        httpHeader.add((CharSequence) "X-USER-DEFINED", "HEADER_VALUE");

        ClientOptions options = ClientOptions.of(ClientOption.HTTP_HEADERS.newValue(httpHeader));
        assertThat(options.get(ClientOption.HTTP_HEADERS), is(Optional.of(httpHeader)));

        ClientOptions options2 = ClientOptions.DEFAULT;
        assertThat(options2.get(ClientOption.HTTP_HEADERS), is(Optional.empty()));

    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetBlackListHeader() {
        HttpHeaders httpHeader = new DefaultHttpHeaders();
        httpHeader.add(HttpHeaderNames.HOST, "localhost");

        ClientOptions.of(ClientOption.HTTP_HEADERS.newValue(httpHeader));
    }

    @Test(expected = NullPointerException.class)
    public void testInvalidOption() {
        ClientOptions.of(ClientOption.RESPONSE_TIMEOUT_POLICY.newValue(null));
    }

}
