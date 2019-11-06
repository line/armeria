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
        ManagedArmeriaServer managedArmeriaServer = new ManagedArmeriaServer<>(configuration, this);
        environment.lifecycle().manage(managedArmeriaServer);
    }

    @Override
    public abstract void onServerBuilderReady(ServerBuilder builder);
}
