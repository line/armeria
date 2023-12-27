/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.xds;

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.grpc.Status;

/**
 * A listener implementation which waits for the updates on the xDS resource.
 * This listener can be added to a {@link XdsBootstrap} via
 * {@link XdsBootstrap#addClusterWatcher(String, ResourceWatcher)} to listen for updates.
 */
@UnstableApi
public interface ResourceWatcher<T extends ResourceHolder<?>> {

    /**
     * Invoked when an unexpected error occurs while parsing a resource.
     */
    default void onError(XdsType type, Status error) {}

    /**
     * Invoked when a resource is deemed not to exist. This can either be due to
     * a timeout on a watch, or the xDS control plane explicitly signalling a resource is missing.
     */
    default void onResourceDoesNotExist(XdsType type, String resourceName) {}

    /**
     * Invoked when a resource is updated.
     */
    void onChanged(T update);
}
