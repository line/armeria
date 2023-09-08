/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.internal.client;

import com.linecorp.armeria.common.util.Version;

import io.netty.util.AsciiString;

public final class UserAgentUtil {

    private static final String CLIENT_ARTIFACT_ID = "armeria";

    public static final AsciiString USER_AGENT = AsciiString.cached(createUserAgentName());

    private static String createUserAgentName() {
        final Version version = Version.get(CLIENT_ARTIFACT_ID, UserAgentUtil.class.getClassLoader());
        return CLIENT_ARTIFACT_ID + '/' + version.artifactVersion();
    }

    private UserAgentUtil() {}
}
