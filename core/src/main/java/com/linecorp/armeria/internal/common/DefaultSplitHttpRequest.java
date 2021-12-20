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

package com.linecorp.armeria.internal.common;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SplitHttpRequest;

import io.netty.util.concurrent.EventExecutor;

public final class DefaultSplitHttpRequest extends AbstractSplitHttpMessage implements SplitHttpRequest {

    private final RequestHeaders headers;

    public DefaultSplitHttpRequest(HttpRequest request, EventExecutor executor) {
        super(request, executor, new SplitHttpMessageSubscriber(0, request, executor));
        headers = request.headers();
    }

    @Override
    public RequestHeaders headers() {
        return headers;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("headers", headers)
                          .add("upstream", upstream)
                          .toString();
    }
}
