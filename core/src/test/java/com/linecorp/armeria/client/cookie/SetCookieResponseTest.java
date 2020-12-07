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

package com.linecorp.armeria.client.cookie;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;

class SetCookieResponseTest {

    private static final ResponseHeaders RESPONSE_HEADERS = ResponseHeaders.of(
            HttpStatus.OK,
            HttpHeaderNames.SET_COOKIE, Cookie.of("cookie1", "value1").toSetCookieHeader(),
            HttpHeaderNames.SET_COOKIE, Cookie.of("cookie2", "value2").toSetCookieHeader(),
            HttpHeaderNames.SET_COOKIE, Cookie.of("cookie3", "value3").toSetCookieHeader());

    @Test
    void testSetCookieHeader() {
        final HttpResponse delegate = HttpResponse.of(RESPONSE_HEADERS);
        final AtomicReference<List<String>> headers = new AtomicReference<>();
        final HttpResponse response = new SetCookieResponse(delegate, headers::set);
        response.aggregate().join();
        assertThat(headers.get()).contains("cookie1=value1", "cookie2=value2", "cookie3=value3");
    }
}
