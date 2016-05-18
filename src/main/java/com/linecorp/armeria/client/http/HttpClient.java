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

package com.linecorp.armeria.client.http;

import java.nio.charset.Charset;

import com.linecorp.armeria.client.ClientOptionDerivable;
import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.DefaultHttpRequest;
import com.linecorp.armeria.common.http.HttpData;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;

public interface HttpClient extends ClientOptionDerivable<HttpClient> {

    HttpResponse execute(HttpRequest req);

    default HttpResponse execute(AggregatedHttpMessage aggregatedReq) {
        final HttpHeaders headers = aggregatedReq.headers();
        final DefaultHttpRequest req = new DefaultHttpRequest(headers);
        final HttpData content = aggregatedReq.content();

        // Add content if not empty.
        if (!content.isEmpty()) {
            headers.setInt(HttpHeaderNames.CONTENT_LENGTH, content.length());
            req.write(content);
        }

        // Add trailing headers if not empty.
        final HttpHeaders trailingHeaders = aggregatedReq.trailingHeaders();
        if (!trailingHeaders.isEmpty()) {
            req.write(trailingHeaders);
        }

        req.close();
        return execute(req);
    }

    default HttpResponse execute(HttpHeaders headers) {
        return execute(AggregatedHttpMessage.of(headers));
    }

    default HttpResponse execute(HttpHeaders headers, HttpData content) {
        return execute(AggregatedHttpMessage.of(headers, content));
    }

    default HttpResponse execute(HttpHeaders headers, byte[] content) {
        return execute(AggregatedHttpMessage.of(headers, HttpData.of(content)));
    }

    default HttpResponse execute(HttpHeaders headers, String content) {
        return execute(AggregatedHttpMessage.of(headers, HttpData.ofUtf8(content)));
    }

    default HttpResponse execute(HttpHeaders headers, String content, Charset charset) {
        return execute(AggregatedHttpMessage.of(headers, HttpData.of(charset, content)));
    }

    default HttpResponse options(String path) {
        return execute(HttpHeaders.of(HttpMethod.OPTIONS, path));
    }

    default HttpResponse get(String path) {
        return execute(HttpHeaders.of(HttpMethod.GET, path));
    }

    default HttpResponse head(String path) {
        return execute(HttpHeaders.of(HttpMethod.HEAD, path));
    }

    default HttpResponse post(String path, HttpData content) {
        return execute(HttpHeaders.of(HttpMethod.POST, path), content);
    }

    default HttpResponse post(String path, byte[] content) {
        return execute(HttpHeaders.of(HttpMethod.POST, path), content);
    }

    default HttpResponse post(String path, String content) {
        return execute(HttpHeaders.of(HttpMethod.POST, path), HttpData.ofUtf8(content));
    }

    default HttpResponse post(String path, String content, Charset charset) {
        return execute(HttpHeaders.of(HttpMethod.POST, path), content, charset);
    }

    default HttpResponse put(String path, HttpData content) {
        return execute(HttpHeaders.of(HttpMethod.PUT, path), content);
    }

    default HttpResponse put(String path, byte[] content) {
        return execute(HttpHeaders.of(HttpMethod.PUT, path), content);
    }

    default HttpResponse put(String path, String content) {
        return execute(HttpHeaders.of(HttpMethod.PUT, path), HttpData.ofUtf8(content));
    }

    default HttpResponse put(String path, String content, Charset charset) {
        return execute(HttpHeaders.of(HttpMethod.PUT, path), content, charset);
    }

    default HttpResponse patch(String path, HttpData content) {
        return execute(HttpHeaders.of(HttpMethod.PATCH, path), content);
    }

    default HttpResponse patch(String path, byte[] content) {
        return execute(HttpHeaders.of(HttpMethod.PATCH, path), content);
    }

    default HttpResponse patch(String path, String content) {
        return execute(HttpHeaders.of(HttpMethod.PATCH, path), HttpData.ofUtf8(content));
    }

    default HttpResponse patch(String path, String content, Charset charset) {
        return execute(HttpHeaders.of(HttpMethod.PATCH, path), content, charset);
    }

    default HttpResponse delete(String path) {
        return execute(HttpHeaders.of(HttpMethod.DELETE, path));
    }

    default HttpResponse trace(String path) {
        return execute(HttpHeaders.of(HttpMethod.TRACE, path));
    }
}
