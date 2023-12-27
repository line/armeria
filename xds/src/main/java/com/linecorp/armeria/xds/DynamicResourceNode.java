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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.Message;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.grpc.Status;

abstract class DynamicResourceNode<T extends Message, U extends ResourceHolder<T>>
        implements ResourceNode<U> {

    private static final Logger logger = LoggerFactory.getLogger(DynamicResourceNode.class);

    static ResourceNode<?> from(XdsType type, XdsBootstrapImpl xdsBootstrap) {
        if (type == XdsType.LISTENER) {
            return new ListenerResourceNode(xdsBootstrap);
        } else if (type == XdsType.ROUTE) {
            return new RouteResourceNode(xdsBootstrap);
        } else if (type == XdsType.CLUSTER) {
            return new ClusterResourceNode(xdsBootstrap);
        } else if (type == XdsType.ENDPOINT) {
            return new EndpointResourceNode(xdsBootstrap);
        } else {
            throw new IllegalArgumentException("Unsupported type: " + type);
        }
    }

    private final XdsBootstrapImpl xdsBootstrap;
    @Nullable
    private U current;

    public Deque<SafeCloseable> safeCloseables() {
        return safeCloseables;
    }

    private final Deque<SafeCloseable> safeCloseables = new ArrayDeque<>();
    boolean initialized;

    DynamicResourceNode(XdsBootstrapImpl xdsBootstrap) {
        this.xdsBootstrap = xdsBootstrap;
    }

    @Override
    public void close() {
        cleanupPreviousWatchers();
    }

    public XdsBootstrapImpl xdsBootstrap() {
        return xdsBootstrap;
    }

    void setCurrent(@Nullable U t) {
        current = t;
    }

    public U current() {
        return current;
    }

    @Override
    public void onError(XdsType type, Status error) {
        logger.warn("Unexpected error while watching {}: {}.", type, error);
    }

    @Override
    public void onResourceDoesNotExist(XdsType type, String resourceName) {
        initialized = true;
        cleanupPreviousWatchers();
        setCurrent(null);
    }

    @Override
    public final void onChanged(U update) {
        initialized = true;
        setCurrent(update);

        final List<SafeCloseable> prevSafeCloseables = new ArrayList<>(safeCloseables);
        safeCloseables.clear();

        process(update);

        // Run the previous closeables after processing so that a resource is updated
        // seamlessly without a onResourceDoesNotExist call
        for (SafeCloseable safeCloseable: prevSafeCloseables) {
            safeCloseable.close();
        }
    }

    abstract void process(U update);

    void cleanupPreviousWatchers() {
        while (!safeCloseables.isEmpty()) {
            safeCloseables.poll().close();
        }
    }

    @Override
    public boolean initialized() {
        return initialized;
    }
}
