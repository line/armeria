/*
 * Copyright 2022 LINE Corporation
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
package com.linecorp.armeria.spring.web.reactive;

import java.util.Map.Entry;

import org.springframework.http.HttpHeaders;

import com.linecorp.armeria.common.HttpHeaderNames;

import io.netty.util.AsciiString;

final class ArmeriaHttpHeadersUtil {

    static HttpHeaders fromArmeriaHttpHeaders(com.linecorp.armeria.common.HttpHeaders armeriaHeaders) {
        final HttpHeaders springHeaders = new HttpHeaders();
        for (Entry<AsciiString, String> e : armeriaHeaders) {
            final AsciiString k = e.getKey();
            final String v = e.getValue();

            if (k.isEmpty()) {
                continue;
            }

            if (k.charAt(0) != ':') {
                springHeaders.add(k.toString(), v);
            } else if (HttpHeaderNames.AUTHORITY.equals(k) && !armeriaHeaders.contains(HttpHeaderNames.HOST)) {
                // Convert `:authority` to `host`.
                springHeaders.add(HttpHeaderNames.HOST.toString(), v);
            }
        }
        return springHeaders;
    }

    private ArmeriaHttpHeadersUtil() {}
}
