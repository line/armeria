/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.client.kubernetes;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;

import io.fabric8.kubernetes.client.http.AsyncBody;
import io.fabric8.kubernetes.client.http.HttpRequest;
import io.fabric8.kubernetes.client.http.HttpResponse;
import io.fabric8.kubernetes.client.http.StandardHttpRequest;
import io.netty.util.AsciiString;

final class ArmeriaHttpResponse implements HttpResponse<AsyncBody> {

    private final StandardHttpRequest request;
    private final ResponseHeaders responseHeaders;
    private final AsyncBody body;

    @Nullable
    private Map<String, List<String>> headers;

    ArmeriaHttpResponse(StandardHttpRequest request, ResponseHeaders responseHeaders, AsyncBody body) {
        this.request = request;
        this.responseHeaders = responseHeaders;
        this.body = body;
    }

    @Override
    public int code() {
        return responseHeaders.status().code();
    }

    @Override
    public String message() {
        return responseHeaders.status().reasonPhrase();
    }

    @Override
    public AsyncBody body() {
        return body;
    }

    @Override
    public HttpRequest request() {
        return request;
    }

    @Override
    public Optional<HttpResponse<?>> previousResponse() {
        return Optional.empty();
    }

    @Override
    public List<String> headers(String key) {
        return responseHeaders.getAll(key);
    }

    @Override
    public Map<String, List<String>> headers() {
        if (headers != null) {
            return headers;
        }

        final ImmutableMap.Builder<String, List<String>> headersBuilder =
                ImmutableMap.builderWithExpectedSize(responseHeaders.size());
        for (AsciiString name : responseHeaders.names()) {
            headersBuilder.put(name.toString(), responseHeaders.getAll(name));
        }
        return headers = headersBuilder.build();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("responseHeaders", responseHeaders)
                          .add("body", body)
                          .toString();
    }
}
