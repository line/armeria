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
package com.linecorp.armeria.dropwizard;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;

#if DROPWIZARD_1 || DROPWIZARD_2
import io.dropwizard.Configuration;
import io.dropwizard.ConfiguredBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
#else
import io.dropwizard.core.Configuration;
import io.dropwizard.core.ConfiguredBundle;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
#endif

/**
 * A Dropwizard {@link ConfiguredBundle} that routes requests through an
 * Armeria {@link Server} rather than the default Jetty server.
 *
 * @param <C> The Dropwizard {@link Configuration} type.
 */
public abstract class ArmeriaBundle<C extends Configuration>
        implements ConfiguredBundle<C>, ArmeriaServerConfigurator {

    @Override
    public void initialize(Bootstrap<?> bootstrap) {}

    @Override
    public void run(C configuration, Environment environment) throws Exception {
        final ManagedArmeriaServer<C> managedArmeriaServer = new ManagedArmeriaServer<>(configuration, this);
        environment.lifecycle().manage(managedArmeriaServer);
    }

    @Override
    public abstract void configure(ServerBuilder builder);
}
