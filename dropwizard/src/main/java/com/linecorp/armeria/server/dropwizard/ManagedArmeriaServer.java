package com.linecorp.armeria.server.dropwizard;

import java.util.Objects;

import javax.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;

import io.dropwizard.Configuration;
import io.dropwizard.lifecycle.Managed;
import io.dropwizard.server.ServerFactory;

public class ManagedArmeriaServer<T extends Configuration> implements Managed {

    private static final Logger LOGGER = LoggerFactory.getLogger(ManagedArmeriaServer.class);

    @Valid
    private final T configuration;
    private final BuilderCallback builderCallback;
    private Server s;

    public ManagedArmeriaServer(final T configuration,
                                final BuilderCallback builderCallback) {
        this.configuration = configuration;
        this.builderCallback = Objects.requireNonNull(builderCallback, "builderCallback");
    }

    @Override
    public void start() throws Exception {
        LOGGER.trace("Getting Armeria Server Builder");
        final ServerFactory serverFactory = configuration.getServerFactory();
        if (!(serverFactory instanceof ArmeriaServerFactory)) {
            throw new RuntimeException("Cannot manage Ameria Server "
                    + "unless Configuration server.type=" + ArmeriaServerFactory.TYPE);
        }
        ServerBuilder sb = ((ArmeriaServerFactory) serverFactory).getServerBuilder();
        LOGGER.trace("Calling Builder Callback");
        builderCallback.onServerBuilderReady(sb);
        s = sb.build();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Built server {}", s);
        }
        LOGGER.info("Starting Ameria Server");
        s.start().thenRunAsync(() -> LOGGER.info("Started Ameria Server"));
    }

    @Override
    public void stop() throws Exception {
        if (s != null) {
            LOGGER.info("Stopping Ameria Server");
            s.stop().thenRunAsync(() -> LOGGER.info("Stopped Ameria Server"));
        }
    }

    public interface BuilderCallback {
        void onServerBuilderReady(ServerBuilder builder);
    }
}
