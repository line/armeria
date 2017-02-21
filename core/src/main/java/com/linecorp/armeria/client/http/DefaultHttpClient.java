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

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.UserClient;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.DefaultHttpRequest;
import com.linecorp.armeria.common.http.DefaultHttpResponse;
import com.linecorp.armeria.common.http.HttpData;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;

import io.netty.channel.EventLoop;

final class DefaultHttpClient extends UserClient<HttpRequest, HttpResponse> implements HttpClient {

    DefaultHttpClient(ClientBuilderParams params,
                      Client<HttpRequest, HttpResponse> delegate,
                      SessionProtocol sessionProtocol, Endpoint endpoint) {
        super(params, delegate, sessionProtocol, endpoint);
    }

    @Override
    public HttpResponse execute(HttpRequest req) {
        return execute(eventLoop(), req);
    }

    private HttpResponse execute(EventLoop eventLoop, HttpRequest req) {
        final String path = concatPaths(uri().getPath(), req.path());
        req.path(path);

        return execute(eventLoop, req.method().name(), req.path(), "", req, cause -> {
            final DefaultHttpResponse res = new DefaultHttpResponse();
            res.close(cause);
            return res;
        });
    }

    @Override
    public HttpResponse execute(AggregatedHttpMessage aggregatedReq) {
        return execute(eventLoop(), aggregatedReq);
    }

    HttpResponse execute(EventLoop eventLoop, AggregatedHttpMessage aggregatedReq) {
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
        return execute(eventLoop, req);
    }

    @VisibleForTesting
    static String concatPaths(String path1, String path2) {
        path2 = path2 == null ? "" : path2;

        if (path1 == null || path1.isEmpty() || "/".equals(path1)) {
            if (path2.isEmpty()) {
                return "/";
            }

            if (path2.charAt(0) == '/') {
                return path2; // Most requests will land here.
            }

            return new StringBuilder(path2.length() + 1)
                    .append('/').append(path2).toString();
        }

        // At this point, we are sure path1 is neither empty nor null.
        if (path2.isEmpty()) {
            // Only path1 is non-empty. No need to concatenate.
            return path1;
        }

        if (path1.charAt(path1.length() - 1) == '/') {
            if (path2.charAt(0) == '/') {
                // path1 ends with '/' and path2 starts with '/'.
                // Avoid double-slash by stripping the first slash of path2.
                return new StringBuilder(path1.length() + path2.length() - 1)
                        .append(path1).append(path2, 1, path2.length()).toString();
            }

            // path1 ends with '/' and path2 does not start with '/'.
            // Simple concatenation would suffice.
            return new StringBuilder(path1.length() + path2.length())
                    .append(path1).append(path2).toString();
        }

        if (path2.charAt(0) == '/') {
            // path1 does not end with '/' and path2 starts with '/'.
            // Simple concatenation would suffice.
            return path1 + path2;
        }

        // path1 does not end with '/' and path2 does not start with '/'.
        // Need to insert '/' between path1 and path2.
        return new StringBuilder(path1.length() + path2.length() + 1)
                .append(path1).append('/').append(path2).toString();
    }
}
