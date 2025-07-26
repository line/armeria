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

package com.linecorp.armeria.internal.common.athenz;

import com.linecorp.armeria.common.HttpHeaderNames;

import io.netty.util.AsciiString;

public final class AthenzHeaderNames {

    public static final AsciiString YAHOO_ROLE_AUTH = HttpHeaderNames.of("yahoo-role-auth");

    private AthenzHeaderNames() {}
}
