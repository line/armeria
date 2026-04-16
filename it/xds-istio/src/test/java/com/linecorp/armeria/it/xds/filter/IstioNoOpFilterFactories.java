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

package com.linecorp.armeria.it.xds.filter;

import com.google.protobuf.Any;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.filter.HttpFilterFactory;
import com.linecorp.armeria.xds.filter.XdsHttpFilter;

import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

/**
 * No-op {@link HttpFilterFactory} implementations for Istio/Envoy-specific HTTP filters
 * that Armeria has no built-in factory for. Registered via SPI so they are picked up
 * automatically by {@link com.linecorp.armeria.xds.filter.HttpFilterFactoryRegistry}.
 * Returns {@code null} from {@code create()} so the filter is silently skipped.
 */
public final class IstioNoOpFilterFactories {

    public abstract static class Base implements HttpFilterFactory {
        @Override
        @Nullable
        public XdsHttpFilter create(HttpFilter httpFilter, Any config) {
            return null;
        }
    }

    public static final class IstioStats extends Base {
        @Override
        public String filterName() {
            return "istio.stats";
        }
    }

    public static final class IstioAlpn extends Base {
        @Override
        public String filterName() {
            return "istio.alpn";
        }
    }

    public static final class IstioMetadataExchange extends Base {
        @Override
        public String filterName() {
            return "istio.metadata_exchange";
        }
    }

    public static final class EnvoyFault extends Base {
        @Override
        public String filterName() {
            return "envoy.filters.http.fault";
        }
    }

    public static final class EnvoyCors extends Base {
        @Override
        public String filterName() {
            return "envoy.filters.http.cors";
        }
    }

    public static final class EnvoyGzipCompressor extends Base {
        @Override
        public String filterName() {
            return "envoy.filters.http.compressor.gzip";
        }
    }

    public static final class EnvoyZstdCompressor extends Base {
        @Override
        public String filterName() {
            return "envoy.filters.http.compressor.zstd";
        }
    }

    public static final class EnvoyGrpcStats extends Base {
        @Override
        public String filterName() {
            return "envoy.filters.http.grpc_stats";
        }
    }

    private IstioNoOpFilterFactories() {}
}
