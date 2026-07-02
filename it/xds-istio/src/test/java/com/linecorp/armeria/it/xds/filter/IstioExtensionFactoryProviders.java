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

package com.linecorp.armeria.it.xds.filter;

import com.linecorp.armeria.xds.XdsExtensionFactory;
import com.linecorp.armeria.xds.XdsExtensionFactoryProvider;

public class IstioExtensionFactoryProviders {

    public static class IstioStats implements XdsExtensionFactoryProvider {
        @Override
        public XdsExtensionFactory newFactory() {
            return new IstioFilterFactories.IstioStats();
        }
    }

    public static class IstioAlpn implements XdsExtensionFactoryProvider {
        @Override
        public XdsExtensionFactory newFactory() {
            return new IstioFilterFactories.IstioAlpn();
        }
    }

    public static class IstioMetadataExchange implements XdsExtensionFactoryProvider {
        @Override
        public XdsExtensionFactory newFactory() {
            return new IstioFilterFactories.IstioMetadataExchange();
        }
    }

    public static class EnvoyFault implements XdsExtensionFactoryProvider {
        @Override
        public XdsExtensionFactory newFactory() {
            return new IstioFilterFactories.EnvoyFault();
        }
    }

    public static class EnvoyCors implements XdsExtensionFactoryProvider {
        @Override
        public XdsExtensionFactory newFactory() {
            return new IstioFilterFactories.EnvoyCors();
        }
    }

    public static class EnvoyGzipCompressor implements XdsExtensionFactoryProvider {
        @Override
        public XdsExtensionFactory newFactory() {
            return new IstioFilterFactories.EnvoyGzipCompressor();
        }
    }

    public static class EnvoyZstdCompressor implements XdsExtensionFactoryProvider {
        @Override
        public XdsExtensionFactory newFactory() {
            return new IstioFilterFactories.EnvoyZstdCompressor();
        }
    }

    public static class EnvoyBrotliCompressor implements XdsExtensionFactoryProvider {
        @Override
        public XdsExtensionFactory newFactory() {
            return new IstioFilterFactories.EnvoyBrotliCompressor();
        }
    }

    public static class EnvoyGrpcStats implements XdsExtensionFactoryProvider {
        @Override
        public XdsExtensionFactory newFactory() {
            return new IstioFilterFactories.EnvoyGrpcStats();
        }
    }
}
