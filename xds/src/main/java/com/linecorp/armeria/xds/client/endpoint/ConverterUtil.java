/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.xds.client.endpoint;

import com.linecorp.armeria.common.HttpMethod;

import io.envoyproxy.envoy.config.core.v3.RequestMethod;

final class ConverterUtil {

    static HttpMethod convert(RequestMethod method, HttpMethod defaultMethod) {
        switch (method) {
            case METHOD_UNSPECIFIED:
                return defaultMethod;
            case GET:
                return HttpMethod.GET;
            case HEAD:
                return HttpMethod.HEAD;
            case POST:
                return HttpMethod.POST;
            case PUT:
                return HttpMethod.PUT;
            case DELETE:
                return HttpMethod.DELETE;
            case CONNECT:
                return HttpMethod.CONNECT;
            case OPTIONS:
                return HttpMethod.OPTIONS;
            case TRACE:
                return HttpMethod.TRACE;
            case PATCH:
                return HttpMethod.PATCH;
            case UNRECOGNIZED:
            default:
                return HttpMethod.UNKNOWN;
        }
    }

    private ConverterUtil() {}
}
