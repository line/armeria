/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.QueryParams;

final class WebClientUtil {

    /**
     * Appends query params to the current path and returns the modified path.
     */
    static String addQueryParams(String path, QueryParams params) {
        requireNonNull(params, "params");
        if (params.isEmpty()) {
            return path;
        }

        final StringBuilder appendedPath = new StringBuilder(path);
        if (path.indexOf('?') == -1) {
            appendedPath.append('?');
        } else {
            appendedPath.append('&');
        }
        return params.appendQueryString(appendedPath).toString();
    }

    private WebClientUtil() {}
}
