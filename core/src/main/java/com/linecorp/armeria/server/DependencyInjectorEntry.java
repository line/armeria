/*
 * Copyright 2022 LINE Corporation
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
package com.linecorp.armeria.server;

/**
 * The {@link DependencyInjector} and {@code shutdownOnStop} that tells whether
 * the {@link DependencyInjector} is shut down when the {@link Server} stops.
 */
public final class DependencyInjectorEntry {

    private final DependencyInjector dependencyInjector;
    private final boolean shutdownOnStop;

    DependencyInjectorEntry(DependencyInjector dependencyInjector, boolean shutdownOnStop) {
        this.dependencyInjector = dependencyInjector;
        this.shutdownOnStop = shutdownOnStop;
    }

    /**
     * Returns the {@link DependencyInjector}.
     */
    public DependencyInjector dependencyInjector() {
        return dependencyInjector;
    }

    /**
     * Returns {@code shutdownOnStop} that tells wheter the {@link DependencyInjector} is
     * shut down when the {@link Server} stops.
     */
    public boolean shutdownOnStop() {
        return shutdownOnStop;
    }
}
