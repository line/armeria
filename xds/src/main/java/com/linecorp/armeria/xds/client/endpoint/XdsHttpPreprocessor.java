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

package com.linecorp.armeria.xds.client.endpoint;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.client.HttpPreprocessor;
import com.linecorp.armeria.client.PreClient;
import com.linecorp.armeria.client.PreClientRequestContext;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.RouteCluster;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.XdsBootstrapRegistry;
import com.linecorp.armeria.xds.internal.XdsCommonUtil;

/**
 * An {@link HttpPreprocessor} implementation which allows clients to execute requests based on
 * xDS (* Discovery Service). A typical user may make requests like the following:
 * <pre>{@code
 * XdsBootstrap bootstrap = XdsBootstrap.of(...);
 * XdsHttpPreprocessor httpPreprocessor = XdsHttpPreprocessor.ofListener("my-listener", bootstrap);
 * WebClient client = WebClient.of(httpPreprocessor);
 * client.get("/"); // the request will be routed based on how the listener "my-listener" is configured
 * httpPreprocessor.close();
 * }</pre>
 * Once a {@link XdsHttpPreprocessor} is no longer used, invoking {@link XdsHttpPreprocessor#close()}
 * may help save resources.
 */
@UnstableApi
public final class XdsHttpPreprocessor extends XdsPreprocessor<HttpRequest, HttpResponse>
        implements HttpPreprocessor {

    /**
     * Returns an {@link HttpPreprocessor} for the specified listener name using the default
     * {@link XdsBootstrap} registered via {@link XdsBootstrapRegistry}.
     */
    public static HttpPreprocessor ofListener(String listenerName) {
        return XdsBootstrapRegistry.httpPreprocessor(XdsBootstrapRegistry.defaultName(), listenerName);
    }

    /**
     * Returns an {@link HttpPreprocessor} for the specified bootstrap and listener name
     * from the {@link XdsBootstrapRegistry}.
     */
    public static HttpPreprocessor ofListener(String bootstrapName, String listenerName) {
        return XdsBootstrapRegistry.httpPreprocessor(bootstrapName, listenerName);
    }

    /**
     * Creates a {@link XdsHttpPreprocessor} with an explicit {@link XdsBootstrap}.
     */
    public static XdsHttpPreprocessor ofListener(String listenerName, XdsBootstrap xdsBootstrap) {
        requireNonNull(listenerName, "listenerName");
        requireNonNull(xdsBootstrap, "xdsBootstrap");
        return new XdsHttpPreprocessor(listenerName, xdsBootstrap);
    }

    private XdsHttpPreprocessor(String listenerName, XdsBootstrap xdsBootstrap) {
        super(listenerName, xdsBootstrap, HttpResponse::of);
    }

    @Override
    HttpResponse execute1(PreClient<HttpRequest, HttpResponse> delegate, PreClientRequestContext ctx,
                          HttpRequest req, RouteCluster routeCluster) throws Exception {
        ctx.setAttr(XdsCommonUtil.ROUTE_METADATA_MATCH, routeCluster.metadataMatch());
        return routeCluster.clusterSnapshot().preprocessor().execute(delegate, ctx, req);
    }
}
