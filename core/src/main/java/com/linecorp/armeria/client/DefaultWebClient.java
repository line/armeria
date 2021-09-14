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

package com.linecorp.armeria.client;

import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.concatPaths;
import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.isAbsoluteUri;
import static java.util.Objects.requireNonNull;

import java.net.URI;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.PathAndQuery;

import io.micrometer.core.instrument.MeterRegistry;

final class DefaultWebClient extends UserClient<HttpRequest, HttpResponse> implements WebClient {

    static final WebClient DEFAULT = new WebClientBuilder().build();

    DefaultWebClient(ClientBuilderParams params, HttpClient delegate, MeterRegistry meterRegistry) {
        super(params, delegate, meterRegistry,
              HttpResponse::from, (ctx, cause) -> HttpResponse.ofFailure(cause));
    }

    @Override
    public HttpResponse execute(HttpRequest req, RequestOptions requestOptions) {
        requireNonNull(req, "req");
        requireNonNull(requestOptions, "requestOptions");

        if (Clients.isUndefinedUri(uri())) {
            final URI uri;
            if (isAbsoluteUri(req.path())) {
                try {
                    uri = URI.create(req.path());
                } catch (Exception ex) {
                    return abortRequestAndReturnFailureResponse(req, new IllegalArgumentException(
                            "Failed to create a URI: " + req.path(), ex));
                }
            } else if (req.scheme() != null && req.authority() != null) {
                uri = req.uri();
            } else {
                return abortRequestAndReturnFailureResponse(req, new IllegalArgumentException(
                        "Scheme and authority must be specified in \":path\" or " +
                        "in \":scheme\" and \":authority\". :path=" +
                        req.path() + ", :scheme=" + req.scheme() + ", :authority=" + req.authority()));
            }
            final SessionProtocol protocol;
            try {
                protocol = Scheme.parse(uri.getScheme()).sessionProtocol();
            } catch (Exception e) {
                return abortRequestAndReturnFailureResponse(req, new IllegalArgumentException(
                        "Failed to parse a scheme: " + uri.getScheme(), e));
            }

            final Endpoint endpoint = Endpoint.parse(uri.getAuthority());
            final String query = uri.getRawQuery();
            final String path = pathWithQuery(uri, query);
            final HttpRequest newReq = req.withHeaders(req.headers().toBuilder().path(path));
            return execute(endpoint, newReq, protocol, requestOptions);
        }

        if (isAbsoluteUri(req.path())) {
            return abortRequestAndReturnFailureResponse(req, new IllegalArgumentException(
                    "Cannot send a request with a \":path\" header that contains a URI with the authority, " +
                    "because the client was created with a base URI. path: " + req.path()));
        }

        final String originalPath = req.path();
        final String newPath = concatPaths(uri().getRawPath(), originalPath);
        final HttpRequest newReq;
        // newPath and originalPath should be the same reference if uri().getRawPath() can be ignorable
        if (newPath != originalPath) {
            newReq = req.withHeaders(req.headers().toBuilder().path(newPath));
        } else {
            newReq = req;
        }
        return execute(endpointGroup(), newReq, scheme().sessionProtocol(), requestOptions);
    }

    private HttpResponse execute(EndpointGroup endpointGroup, HttpRequest req, SessionProtocol protocol,
                                 RequestOptions requestOptions) {
        final PathAndQuery pathAndQuery = PathAndQuery.parse(req.path());
        if (pathAndQuery == null) {
            final IllegalArgumentException cause = new IllegalArgumentException("invalid path: " + req.path());
            return abortRequestAndReturnFailureResponse(req, cause);
        }
        final String newPath = pathAndQuery.toString();
        if (!newPath.equals(req.path())) {
            req = req.withHeaders(req.headers().toBuilder().path(newPath));
        }
        return execute(protocol, endpointGroup, req.method(),
                       pathAndQuery.path(), pathAndQuery.query(), null, req, requestOptions);
    }

    @Override
    public HttpResponse execute(AggregatedHttpRequest aggregatedReq) {
        return execute(aggregatedReq.toHttpRequest());
    }

    private static HttpResponse abortRequestAndReturnFailureResponse(
            HttpRequest req, IllegalArgumentException cause) {
        req.abort(cause);
        return HttpResponse.ofFailure(cause);
    }

    @Override
    public HttpClient unwrap() {
        return (HttpClient) super.unwrap();
    }

    static String pathWithQuery(URI uri, @Nullable String query) {
        String path = uri.getRawPath();
        if (Strings.isNullOrEmpty(path)) {
            path = query == null ? "/" : "/?" + query;
        } else if (query != null) {
            path = path + '?' + query;
        }
        return path;
    }
}
