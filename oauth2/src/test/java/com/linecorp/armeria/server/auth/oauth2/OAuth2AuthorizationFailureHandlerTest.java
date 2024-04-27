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

package com.linecorp.armeria.server.auth.oauth2;

import static com.linecorp.armeria.server.auth.oauth2.OAuth2TokenIntrospectionAuthorizer.ERROR_CODE;
import static com.linecorp.armeria.server.auth.oauth2.OAuth2TokenIntrospectionAuthorizer.ERROR_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

class OAuth2AuthorizationFailureHandlerTest {

    @Test
    void testInvalidRequest() throws Exception {
        final OAuth2AuthorizationFailureHandler handler =
            new OAuth2AuthorizationFailureHandler("Bearer", "test realm", "read write");

        final RequestHeaders headers = RequestHeaders.of(
                HttpMethod.POST, "/",
                HttpHeaderNames.AUTHORIZATION, "Basic YWxhZGRpbjpvcGVuc2VzYW1l");
        final HttpRequest request = HttpRequest.of(headers);
        final ServiceRequestContext context = ServiceRequestContext.of(request);
        context.setAttr(ERROR_CODE, 400);
        context.setAttr(ERROR_TYPE, "unsupported_token_type");
        final HttpResponse response = handler.authFailed(new MyService(), context, request, null);
        final AggregatedHttpResponse aggregatedResponse = response.aggregate().join();
        assertThat(aggregatedResponse.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(aggregatedResponse.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(aggregatedResponse.content().toStringUtf8())
                .isEqualTo("{\"error\":\"unsupported_token_type\"}");
    }

    @Test
    void testInvalidToken() throws Exception {
        final OAuth2AuthorizationFailureHandler handler =
            new OAuth2AuthorizationFailureHandler("Bearer", "test realm", "read write");

        final RequestHeaders headers = RequestHeaders.of(
                HttpMethod.POST, "/",
                HttpHeaderNames.AUTHORIZATION, "Bearer mF_9.B5f-4.1JqM");
        final HttpRequest request = HttpRequest.of(headers);
        final ServiceRequestContext context = ServiceRequestContext.of(request);
        context.setAttr(ERROR_CODE, 401);
        context.setAttr(ERROR_TYPE, "invalid_token");
        final HttpResponse response = handler.authFailed(new MyService(), context, request, null);
        final AggregatedHttpResponse aggregatedResponse = response.aggregate().join();
        assertThat(aggregatedResponse.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(aggregatedResponse.headers().get(HttpHeaderNames.WWW_AUTHENTICATE))
                .isEqualTo("Bearer realm=\"test realm\", error=\"invalid_token\", scope=\"read write\"");
        assertThat(aggregatedResponse.content().isEmpty()).isTrue();
    }

    @Test
    void testInsufficientScope() throws Exception {
        final OAuth2AuthorizationFailureHandler handler =
            new OAuth2AuthorizationFailureHandler("Bearer", "test realm", "read write");

        final RequestHeaders headers = RequestHeaders.of(
                HttpMethod.POST, "/",
                HttpHeaderNames.AUTHORIZATION, "Bearer mF_9.B5f-4.1JqM");
        final HttpRequest request = HttpRequest.of(headers);
        final ServiceRequestContext context = ServiceRequestContext.of(request);
        context.setAttr(ERROR_CODE, 403);
        context.setAttr(ERROR_TYPE, "insufficient_scope");
        final HttpResponse response = handler.authFailed(new MyService(), context, request, null);
        final AggregatedHttpResponse aggregatedResponse = response.aggregate().join();
        assertThat(aggregatedResponse.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(aggregatedResponse.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(aggregatedResponse.content().toStringUtf8())
                .isEqualTo("{\"error\":\"insufficient_scope\"}");
    }

    @Test
    void testError() throws Exception {
        final OAuth2AuthorizationFailureHandler handler =
            new OAuth2AuthorizationFailureHandler("Bearer", "test realm", "read write");

        final RequestHeaders headers = RequestHeaders.of(
                HttpMethod.POST, "/",
                HttpHeaderNames.AUTHORIZATION, "Bearer mF_9.B5f-4.1JqM");
        final HttpRequest request = HttpRequest.of(headers);
        final ServiceRequestContext context = ServiceRequestContext.of(request);
        final HttpResponse response = handler.authFailed(new MyService(), context, request, new Exception());
        final AggregatedHttpResponse aggregatedResponse = response.aggregate().join();
        assertThat(aggregatedResponse.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(aggregatedResponse.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(aggregatedResponse.content().toStringUtf8())
                .isEqualTo("Unexpected exception during OAuth 2 authorization.");
    }

    @Test
    void testOther() throws Exception {
        final OAuth2AuthorizationFailureHandler handler =
            new OAuth2AuthorizationFailureHandler("Bearer", "test realm", "read write");

        final RequestHeaders headers = RequestHeaders.of(
                HttpMethod.POST, "/",
                HttpHeaderNames.AUTHORIZATION, "Bearer mF_9.B5f-4.1JqM");
        final HttpRequest request = HttpRequest.of(headers);
        final ServiceRequestContext context = ServiceRequestContext.of(request);
        context.setAttr(ERROR_CODE, 404);
        final HttpResponse response = handler.authFailed(new MyService(), context, request, null);
        final AggregatedHttpResponse aggregatedResponse = response.aggregate().join();
        assertThat(aggregatedResponse.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(aggregatedResponse.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(aggregatedResponse.content().toStringUtf8())
                .isEqualTo(HttpStatus.NOT_FOUND.toString());
    }

    static class MyService extends AbstractHttpService {
    }
}
