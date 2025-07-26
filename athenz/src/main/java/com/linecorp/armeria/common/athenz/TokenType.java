/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.common.athenz;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.athenz.AthenzHeaderNames;

import io.netty.util.AsciiString;

/**
 * The type of Athenz token.
 */
@UnstableApi
public enum TokenType {
    /**
     * Athenz role token.
     */
    ROLE_TOKEN(AthenzHeaderNames.YAHOO_ROLE_AUTH),
    /**
     * Athenz access token.
     */
    ACCESS_TOKEN(HttpHeaderNames.AUTHORIZATION);

    TokenType(AsciiString headerName) {
        this.headerName = headerName;
    }

    private final AsciiString headerName;

    /**
     * Returns the header name used to pass the token.
     */
    public AsciiString headerName() {
        return headerName;
    }
}
