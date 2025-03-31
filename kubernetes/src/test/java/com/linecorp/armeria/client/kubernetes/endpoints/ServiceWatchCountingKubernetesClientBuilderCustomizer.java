/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.client.kubernetes.endpoints;

import java.util.concurrent.atomic.AtomicInteger;

import com.linecorp.armeria.client.kubernetes.ArmeriaHttpClientFactory;
import com.linecorp.armeria.client.websocket.WebSocketClientBuilder;
import com.linecorp.armeria.internal.common.websocket.WebSocketUtil;

import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.server.mock.KubernetesClientBuilderCustomizer;

public class ServiceWatchCountingKubernetesClientBuilderCustomizer extends KubernetesClientBuilderCustomizer {

    private static final AtomicInteger numRequests = new AtomicInteger();

    static int numRequests() {
        return numRequests.get();
    }

    static void reset() {
        numRequests.set(0);
    }

    @Override
    public void accept(KubernetesClientBuilder kubernetesClientBuilder) {
        kubernetesClientBuilder.withHttpClientFactory(new ArmeriaHttpClientFactory() {

            @Override
            protected void additionalWebSocketConfig(WebSocketClientBuilder builder) {
                builder.decorator((delegate, ctx, req) -> {
                    if (ctx.path().startsWith("/api/v1/namespaces/test/services") &&
                        WebSocketUtil.isHttp1WebSocketUpgradeRequest(req.headers())) {
                        numRequests.incrementAndGet();
                    }
                    return delegate.execute(ctx, req);
                });
            }
        });
    }
}
