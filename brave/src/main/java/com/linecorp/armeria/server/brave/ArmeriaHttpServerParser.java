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

import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.internal.brave.SpanTags;

import brave.SpanCustomizer;
import brave.http.HttpAdapter;
import brave.http.HttpServerParser;

public class ArmeriaHttpServerParser extends HttpServerParser {
    @Override
    public <T> void request(HttpAdapter<T, ?> adapter, T req, SpanCustomizer customizer) {
        super.request(adapter, req, customizer);
        if (req instanceof RequestLog) {
            ((RequestLog) req).addListener(log -> {
                customizer.tag("http.url", adapter.url(req));
            }, RequestLogAvailability.SCHEME);
        }
    }

    @Override
    public <T> void response(HttpAdapter<?, T> adapter, T res, Throwable error, SpanCustomizer customizer) {
        super.response(adapter, res, error, customizer);
        if (res instanceof RequestLog) {
            SpanTags.addCustomTags(customizer, (RequestLog) res);
        }
    }
}
