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

package com.linecorp.armeria.xds.client.endpoint;

import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;

import com.linecorp.armeria.client.HttpPreprocessor;
import com.linecorp.armeria.client.RpcPreprocessor;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.XdsResourceValidator;
import com.linecorp.armeria.xds.filter.HttpFilterFactory;
import com.linecorp.armeria.xds.filter.XdsHttpFilter;

import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

/**
 * A {@link HttpFilterFactory} implementation of the {@link Router} filter.
 */
@UnstableApi
public final class RouterFilterFactory implements HttpFilterFactory {

    private static final String NAME = "envoy.filters.http.router";
    private static final String TYPE_URL =
            "type.googleapis.com/envoy.extensions.filters.http.router.v3.Router";
    private static final RouterFilter<RpcRequest, RpcResponse> rpcFilter = new RouterFilter<>();
    private static final RouterFilter<HttpRequest, HttpResponse> httpFilter = new RouterFilter<>();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> typeUrls() {
        return ImmutableList.of(TYPE_URL);
    }

    @Override
    public XdsHttpFilter create(HttpFilter filter, Any config, XdsResourceValidator validator) {
        final Router router;
        if (config == Any.getDefaultInstance()) {
            router = Router.getDefaultInstance();
        } else {
            router = validator.unpack(config, Router.class);
        }
        return new RouterXdsHttpFilter(router);
    }

    /**
     * An {@link XdsHttpFilter} that holds the parsed {@link Router} config.
     */
    @UnstableApi
    public static final class RouterXdsHttpFilter implements XdsHttpFilter {

        private final Router router;

        RouterXdsHttpFilter(Router router) {
            this.router = router;
        }

        /**
         * Returns the {@link Router} config.
         */
        public Router router() {
            return router;
        }

        @Override
        public HttpPreprocessor httpPreprocessor() {
            return httpFilter::execute;
        }

        @Override
        public RpcPreprocessor rpcPreprocessor() {
            return rpcFilter::execute;
        }
    }
}
