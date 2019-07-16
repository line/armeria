/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.server.brave;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.internal.brave.SpanTags;

import brave.http.HttpServerAdapter;

final class ArmeriaHttpServerAdapter extends HttpServerAdapter<RequestLog, RequestLog> {
    @Override
    public String path(RequestLog requestLog) {
        return requestLog.path();
    }

    @Override
    public String method(RequestLog requestLog) {
        return requestLog.method().name();
    }

    @Override
    public String url(RequestLog requestLog) {
        return SpanTags.generateUrl(requestLog);
    }

    @Override
    @Nullable
    public String requestHeader(RequestLog requestLog, String name) {
        return requestLog.requestHeaders().get(name);
    }

    @Override
    public Integer statusCode(RequestLog requestLog) {
        return requestLog.status().code();
    }
}
