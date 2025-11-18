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

import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.client.kubernetes.ArmeriaHttpClientFactory;
import com.linecorp.armeria.client.websocket.WebSocketClientBuilder;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.internal.common.websocket.WebSocketFrameEncoder;

import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.server.mock.KubernetesClientBuilderCustomizer;

public class FaultInjectingKubernetesClientBuilderCustomizer extends KubernetesClientBuilderCustomizer {

    private static volatile boolean shouldInjectFault;

    static void injectFault(boolean shouldInjectFault) {
        FaultInjectingKubernetesClientBuilderCustomizer.shouldInjectFault = shouldInjectFault;
    }

    @Override
    public void accept(KubernetesClientBuilder kubernetesClientBuilder) {
        kubernetesClientBuilder.withHttpClientFactory(new ArmeriaHttpClientFactory() {

            @Override
            protected void additionalConfig(WebClientBuilder builder) {
                builder.decorator((delegate, ctx, req) -> {
                    final HttpResponse response = delegate.execute(ctx, req);
                    if (shouldInjectFault && ctx.method() == HttpMethod.GET) {
                        return response.mapData(data -> {
                            data.close();
                            return HttpData.ofUtf8("invalid data");
                        });
                    } else {
                        return response;
                    }
                });
            }

            @Override
            protected void additionalWebSocketConfig(WebSocketClientBuilder builder) {
                builder.decorator((delegate, ctx, req) -> {
                    // Do something with the request.
                    final HttpResponse response = delegate.execute(ctx, req);
                    return response.mapData(object -> {
                        final HttpData newData;
                        if (shouldInjectFault) {
                            object.close();
                            final WebSocketFrameEncoder encoder = WebSocketFrameEncoder.of(false);
                            final WebSocketFrame frame = WebSocketFrame.ofText("invalid data");
                            newData = HttpData.wrap(encoder.encode(ctx, frame));
                        } else {
                            newData = object;
                        }
                        return newData;
                    });
                });
            }
        });
    }
}
