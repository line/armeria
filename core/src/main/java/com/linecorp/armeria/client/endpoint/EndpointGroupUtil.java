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

package com.linecorp.armeria.client.endpoint;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.linecorp.armeria.common.annotation.Nullable;

final class EndpointGroupUtil {

    private static final String ENDPOINT_GROUP_MARK = "group:";
    private static final Pattern ENDPOINT_GROUP_PATTERN = Pattern.compile(
            "://(?:[^@]*@)?(" + ENDPOINT_GROUP_MARK + "([^:/]+)(:\\d+)?)");

    @Nullable
    static String getEndpointGroupName(String uri) {
        final Matcher matcher = ENDPOINT_GROUP_PATTERN.matcher(uri);
        if (matcher.find()) {
            return matcher.group(2);
        }
        return null;
    }

    static String replaceEndpointGroup(String uri, String endpointUri) {
        final Matcher matcher = ENDPOINT_GROUP_PATTERN.matcher(uri);
        if (matcher.find()) {
            return new StringBuilder(uri).replace(matcher.start(1), matcher.end(1), endpointUri).toString();
        }
        return uri;
    }

    private EndpointGroupUtil() {}
}
