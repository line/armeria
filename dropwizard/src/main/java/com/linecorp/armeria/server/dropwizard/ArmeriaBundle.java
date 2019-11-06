/*
 * Copyright 2019 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.server.dropwizard;

import com.linecorp.armeria.server.ServerBuilder;

import io.dropwizard.Configuration;
import io.dropwizard.ConfiguredBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;

public abstract class ArmeriaBundle<C extends Configuration>
        implements ConfiguredBundle<C>, ManagedArmeriaServer.BuilderCallback {

    @Override
    public void initialize(Bootstrap<?> bootstrap) {
    }

    @Override
    public void run(final C configuration, final Environment environment) throws Exception {
        final ManagedArmeriaServer managedArmeriaServer = new ManagedArmeriaServer<>(configuration, this);
        environment.lifecycle().manage(managedArmeriaServer);
    }

    @Override
    public abstract void onServerBuilderReady(ServerBuilder builder);
}
