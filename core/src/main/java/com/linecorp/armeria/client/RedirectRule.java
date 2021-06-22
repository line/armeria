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

import static com.linecorp.armeria.client.RedirectRuleUtil.defaultRedirectRule;

import java.net.URI;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A rule for <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-6.4">automatic redirection</a>.
 * {@link #shouldRedirect(ClientRequestContext, RequestHeaders, ResponseHeaders, URI)} is called when the
 * the status of {@link ResponseHeaders} is {@link HttpStatusClass#REDIRECTION} and
 * the {@link ResponseHeaders} has a valid {@link HttpHeaderNames#LOCATION} header.
 */
@UnstableApi
@FunctionalInterface
public interface RedirectRule {

    /**
     * Returns the default {@link RedirectRule} that changes the {@link HttpMethod} to {@link HttpMethod#GET}
     * for the redirection request when the {@link HttpStatus} of a response is {@link HttpStatus#SEE_OTHER}.
     */
    static RedirectRule of() {
        return defaultRedirectRule;
    }

    /**
     * Returns a new {@link RequestHeaders} that is used to send a redirection request. The redirection URI
     * should be set to the {@link RequestHeaders#path()}. Return {@code null} if you don't want redirection
     * for the specified {@code redirectUri}.
     *
     * @param ctx the {@link ClientRequestContext}
     * @param requestHeaders the {@link RequestHeaders} that is used to send the previous request
     * @param responseHeaders the {@link ResponseHeaders} that contains redirection status and
     *                        the new URI in {@link HttpHeaderNames#LOCATION}
     * @param redirectUri the redirect URI that is resolved using
     *                    <a href="https://datatracker.ietf.org/doc/html/rfc3986#section-5">
     *                    reference resolution</a>
     */
    @Nullable
    RequestHeaders shouldRedirect(ClientRequestContext ctx, RequestHeaders requestHeaders,
                                  ResponseHeaders responseHeaders, URI redirectUri);
}
