/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.client.cookie;

import java.util.List;
import java.util.function.Consumer;

import com.linecorp.armeria.common.FilteredHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;

/**
 * A {@link FilteredHttpResponse} that extracts {@code Set-Cookie} headers.
 */
final class SetCookieResponse extends FilteredHttpResponse {

    private final Consumer<List<String>> consumer;
    private boolean headersReceived;

    SetCookieResponse(HttpResponse delegate, Consumer<List<String>> consumer) {
        super(delegate);
        this.consumer = consumer;
    }

    @Override
    protected HttpObject filter(HttpObject obj) {
        if (!(obj instanceof HttpHeaders)) {
            return obj;
        }
        final HttpHeaders headers = (HttpHeaders) obj;
        final String status = headers.get(HttpHeaderNames.STATUS);
        // Skip informational headers.
        if (ArmeriaHttpUtil.isInformational(status)) {
            return obj;
        }
        // Trailers, no modification.
        if (headersReceived) {
            return obj;
        }
        // Follow-up headers for informational headers, no modification.
        if (status == null) {
            return obj;
        }
        headersReceived = true;
        final List<String> setCookieHeaders = headers.getAll(HttpHeaderNames.SET_COOKIE);
        consumer.accept(setCookieHeaders);
        return headers;
    }
}
