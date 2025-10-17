/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.xds.it;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DecoratingHttpClientFunction;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;

final class EndpointCollectingDecorator implements DecoratingHttpClientFunction {

    private final BlockingQueue<Endpoint> endpointsQueue = new LinkedBlockingQueue<>();

    BlockingQueue<Endpoint> endpointsQueue() {
        return endpointsQueue;
    }

    @Override
    public HttpResponse execute(HttpClient delegate, ClientRequestContext ctx, HttpRequest req)
            throws Exception {
        final Endpoint endpoint = ctx.endpoint();
        if (endpoint == null) {
            return HttpResponse.of(400);
        }
        endpointsQueue.add(endpoint);
        return HttpResponse.of(200);
    }
}
