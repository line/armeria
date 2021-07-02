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

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeadersBuilder;

final class RedirectRuleUtil {

    static final RedirectRule defaultRedirectRule =
            (ctx, requestHeaders, responseHeaders, redirectUri) -> {
                final RequestHeadersBuilder builder = requestHeaders.toBuilder();
                builder.path(redirectUri.toString());
                final HttpMethod method = requestHeaders.method();
                if (responseHeaders.status() == HttpStatus.SEE_OTHER &&
                    !(method == HttpMethod.GET || method == HttpMethod.HEAD)) {
                    // HTTP methods are changed to GET when the status is 303.
                    // https://developer.mozilla.org/en-US/docs/Web/HTTP/Redirections
                    // https://datatracker.ietf.org/doc/html/rfc7231#section-6.4.4
                    //
                    // However, I(minwoox) didn't put this logic into RedirectingClient but here in the default
                    // redirect rule so that users can send the request what they want by implementing
                    // their own RedirectRule.
                    // curl also allows it. https://curl.se/docs/manpage.html#--post303
                    builder.method(HttpMethod.GET);
                }
                return builder.build();
            };

    private RedirectRuleUtil() {}
}
