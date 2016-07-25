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
package com.linecorp.armeria.client.routing;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EndpointGroupUtil {
    private final static String ENDPOINT_GROUP_MARK = "group:";
    private final static Pattern ENDPOINT_GROUP_PATTERN = Pattern.compile("://(?:[^@]*@)?(" + ENDPOINT_GROUP_MARK + "([^:/]+)(:\\d+)?)");

    public final static String getEndpointGroupName(URI uri) {
        return EndpointGroupUtil.getEndpointGroupName(uri.toString());
    }

    public final static String getEndpointGroupName(String uri) {
        Matcher matcher = ENDPOINT_GROUP_PATTERN.matcher(uri);
        if (matcher.find()) {
            return matcher.group(2);
        }
        return null;
    }

    public final static String replaceEndpointGroup(URI uri, String endpointUri) {
        return EndpointGroupUtil.replaceEndpointGroup(uri.toString(), endpointUri);
    }

    public final static String replaceEndpointGroup(String uri, String endpointUri) {
        Matcher matcher = ENDPOINT_GROUP_PATTERN.matcher(uri);
        if (matcher.find()) {
            return new StringBuilder(uri).
                    replace(matcher.start(1), matcher.end(1), endpointUri).toString();

        }
        return uri;
    }

    public final static String removeGroupMark(URI uri) {
        return uri.toString().replaceFirst(ENDPOINT_GROUP_MARK, "");
    }
}
