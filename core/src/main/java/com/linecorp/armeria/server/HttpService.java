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

package com.linecorp.armeria.server;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;

/**
 * An HTTP/2 {@link Service}.
 *
 * <p>This interface is merely a shortcut to {@code Service<HttpRequest, HttpResponse>} at the moment.
 */
@FunctionalInterface
public interface HttpService extends Service<HttpRequest, HttpResponse> {
    @Override
    HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception;
}
