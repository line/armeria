/*
 * Copyright 2016 LINE Corporation
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

import io.netty.util.AsciiString;

final class HttpHeaderUtil {

    // TODO(trustin): Add version information
    static final AsciiString USER_AGENT = AsciiString.of("Armeria");

    static String hostHeader(String host, int port, int defaultPort) {
        if (port == defaultPort) {
            return host;
        }

        return new StringBuilder(host.length() + 6).append(host).append(':').append(port).toString();
    }

    private HttpHeaderUtil() {}
}
