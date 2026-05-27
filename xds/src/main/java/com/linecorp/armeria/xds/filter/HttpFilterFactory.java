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

package com.linecorp.armeria.xds.filter;

import com.google.protobuf.Any;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.XdsExtensionFactory;
import com.linecorp.armeria.xds.stream.SnapshotStream;

import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

/**
 * A factory that creates an {@link XdsHttpFilter} for a given {@link HttpFilter}.
 *
 * <p>Implementations are discovered via the Java {@link java.util.ServiceLoader} mechanism.
 * The raw {@link Any} typed config is passed as-is from xDS — factories are responsible for
 * all config parsing, including unwrapping {@code FilterConfig} envelopes for per-route overrides.
 * Returning {@code null} from {@link #create} causes the filter to be silently skipped.
 */
@UnstableApi
public interface HttpFilterFactory extends XdsExtensionFactory {

    /**
     * Creates an {@link XdsHttpFilter} for the given filter and its raw typed config.
     *
     * <p>For filter-level configs, {@code config} is {@link HttpFilter#getTypedConfig()}.
     * For per-route override configs, {@code config} is the raw {@link Any} from
     * {@code typed_per_filter_config}, which may be a {@code FilterConfig} envelope.
     *
     * <p>Returns {@code null} to skip this filter entirely. The {@link HttpFilter} argument
     * is provided so factories can inspect fields such as {@link HttpFilter#getDisabled()} or
     * {@link HttpFilter#getIsOptional()}.
     *
     * @param httpFilter the filter descriptor from {@link HttpConnectionManager#getHttpFiltersList()}
     * @param config     the raw typed config {@link Any}; may be {@link Any#getDefaultInstance()}
     *                   if no config was provided
     * @param context    the {@link FactoryContext} providing runtime infrastructure such as
     *                   event loop, metrics, and secret subscriptions
     */
    @Nullable
    XdsHttpFilter create(HttpFilter httpFilter, Any config, FactoryContext context);

    /**
     * Creates a {@link SnapshotStream} of {@link XdsHttpFilter} for the given filter and its raw typed config.
     *
     * <p>The default implementation delegates to {@link #create} and wraps the result in
     * {@link SnapshotStream#just}. Override this method for filters that depend on external
     * xDS resources and need reactive lifecycle management.
     *
     * @param httpFilter the filter descriptor from {@link HttpConnectionManager#getHttpFiltersList()}
     * @param config     the raw typed config {@link Any}; may be {@link Any#getDefaultInstance()}
     *                   if no config was provided
     * @param context    the {@link FactoryContext} providing runtime infrastructure such as
     *                   event loop, metrics, and secret subscriptions
     * @return a stream of filter instances
     */
    default SnapshotStream<XdsHttpFilter> createStream(HttpFilter httpFilter, Any config,
                                                       FactoryContext context) {
        final XdsHttpFilter filter = create(httpFilter, config, context);
        if (filter == null) {
            return SnapshotStream.just(XdsHttpFilter.NOOP);
        }
        return SnapshotStream.just(filter);
    }
}
