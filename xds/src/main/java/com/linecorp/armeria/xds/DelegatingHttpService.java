/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.xds;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.util.AttributeKey;

final class DelegatingHttpService implements HttpService {

    private static final DelegatingHttpService INSTANCE = new DelegatingHttpService();

    private static final AttributeKey<HttpService> DELEGATE_KEY =
            AttributeKey.valueOf(DelegatingHttpService.class, "DELEGATE_KEY");

    static DelegatingHttpService of() {
        return INSTANCE;
    }

    static void setDelegate(ServiceRequestContext ctx, HttpService delegate) {
        ctx.setAttr(DELEGATE_KEY, delegate);
    }

    private DelegatingHttpService() {
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final HttpService delegate = ctx.attr(DELEGATE_KEY);
        if (delegate == null) {
            return HttpResponse.ofFailure(
                    new IllegalStateException("No delegate service set on the context"));
        }
        return delegate.serve(ctx, req);
    }
}
