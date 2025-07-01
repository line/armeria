package com.linecorp.armeria.client.kubernetes;

import reactor.blockhound.BlockHound.Builder;
import reactor.blockhound.integration.BlockHoundIntegration;

/**
 * A {@link BlockHoundIntegration} for the Fabric Kubernetes module in tests.
 */
public final class KubernetesTestBlockHoundIntegration implements BlockHoundIntegration {
    @Override
    public void applyTo(Builder builder) {
        builder.allowBlockingCallsInside(
                "io.fabric8.kubernetes.client.server.mock.WatchEventsListener", "onClosed");
    }
}
