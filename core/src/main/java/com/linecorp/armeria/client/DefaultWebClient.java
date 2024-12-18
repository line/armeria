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

import static java.util.Objects.requireNonNull;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.RequestTargetForm;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.client.ClientUtil;
import com.linecorp.armeria.internal.client.DefaultClientRequestContext;
import com.linecorp.armeria.internal.client.TailPreClient;
import com.linecorp.armeria.internal.client.endpoint.FailingEndpointGroup;

import io.micrometer.core.instrument.MeterRegistry;

final class DefaultWebClient extends UserClient<HttpRequest, HttpResponse> implements WebClient {

    static final WebClient DEFAULT = new WebClientBuilder().build();

    static final RequestOptions RESPONSE_STREAMING_REQUEST_OPTIONS =
            RequestOptions.builder()
                          .exchangeType(ExchangeType.RESPONSE_STREAMING)
                          .build();

    @Nullable
    private BlockingWebClient blockingWebClient;
    @Nullable
    private RestClient restClient;
    private final HttpPreClient preClient;

    DefaultWebClient(ClientBuilderParams params, HttpClient delegate, MeterRegistry meterRegistry) {
        super(params, delegate, meterRegistry,
              HttpResponse::of, (ctx, cause) -> HttpResponse.ofFailure(cause));
        final HttpPreClient tailPreClient =
                TailPreClient.of(unwrap(), futureConverter(), errorResponseFactory());
        preClient = options().clientPreprocessors().decorate(tailPreClient);
    }

    @Override
    public HttpResponse execute(HttpRequest req, RequestOptions requestOptions) {
        requireNonNull(req, "req");
        requireNonNull(requestOptions, "requestOptions");

        final String originalPath = req.path();
        final String prefix = Strings.emptyToNull(uri().getRawPath());
        final RequestTarget reqTarget = RequestTarget.forClient(originalPath, prefix);
        if (reqTarget == null) {
            return abortRequestAndReturnFailureResponse(
                    req, new IllegalArgumentException("Invalid request target: " + originalPath));
        }

        final EndpointGroup endpointGroup;
        final SessionProtocol protocol;

        if (Clients.isUndefinedUri(uri())) {
            final String scheme;
            final String authority;
            if (reqTarget.form() == RequestTargetForm.ABSOLUTE) {
                scheme = reqTarget.scheme();
                authority = reqTarget.authority();
                assert scheme != null;
                assert authority != null;
            } else {
                // the scheme and authority may be null if the client is preprocessor-based
                scheme = req.scheme();
                authority = req.authority();
            }

            if (authority != null) {
                endpointGroup = Endpoint.parse(authority);
            } else {
                endpointGroup = FailingEndpointGroup.of();
            }
            if (scheme != null) {
                try {
                    protocol = Scheme.parse(scheme).sessionProtocol();
                } catch (Exception e) {
                    return abortRequestAndReturnFailureResponse(req, new IllegalArgumentException(
                            "Failed to parse a scheme: " + reqTarget.scheme(), e));
                }
            } else {
                protocol = SessionProtocol.UNDEFINED;
            }
        } else {
            if (reqTarget.form() == RequestTargetForm.ABSOLUTE) {
                return abortRequestAndReturnFailureResponse(req, new IllegalArgumentException(
                        "Cannot send a request with a \":path\" header that contains an authority, " +
                        "because the client was created with a base URI. path: " + originalPath));
            }

            endpointGroup = endpointGroup();
            protocol = scheme().sessionProtocol();
        }

        final String newPath = reqTarget.pathAndQuery();
        final HttpRequest newReq;
        if (newPath.equals(originalPath)) {
            newReq = req;
        } else {
            newReq = req.withHeaders(req.headers().toBuilder().path(newPath));
        }

        final DefaultClientRequestContext ctx = new DefaultClientRequestContext(
                protocol, newReq, newReq.method(), null, reqTarget, endpointGroup, requestOptions, options(),
                meterRegistry());
        return ClientUtil.executeWithFallback(preClient, ctx, newReq, errorResponseFactory());
    }

    private static HttpResponse abortRequestAndReturnFailureResponse(
            HttpRequest req, IllegalArgumentException cause) {
        req.abort(cause);
        return HttpResponse.ofFailure(cause);
    }

    @Override
    public BlockingWebClient blocking() {
        if (blockingWebClient != null) {
            return blockingWebClient;
        }
        return blockingWebClient = new DefaultBlockingWebClient(this);
    }

    @Override
    public RestClient asRestClient() {
        if (restClient != null) {
            return restClient;
        }
        return restClient = RestClient.of(this);
    }

    @Override
    public HttpClient unwrap() {
        return (HttpClient) super.unwrap();
    }
}
