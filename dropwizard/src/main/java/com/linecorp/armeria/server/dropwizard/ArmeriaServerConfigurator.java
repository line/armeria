package com.linecorp.armeria.server.dropwizard;

import com.linecorp.armeria.server.ServerBuilder;

public interface ArmeriaServerConfigurator {
    void onServerBuilderReady(ServerBuilder builder);
}
