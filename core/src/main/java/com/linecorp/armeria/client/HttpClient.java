/*
 * Copyright 2019 LINE Corporation
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

import com.google.errorprone.annotations.CheckReturnValue;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;

/**
 * Sends an {@link HttpRequest} to a remote {@link Endpoint}.
 */
@FunctionalInterface
public interface HttpClient extends Client<HttpRequest, HttpResponse> {
    @Override
    @CheckReturnValue
    HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception;
}
