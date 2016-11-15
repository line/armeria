package com.linecorp.armeria.client.routing.endpoint.healthcheck;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.http.HttpClient;
import com.linecorp.armeria.client.routing.EndpointGroup;
import com.linecorp.armeria.common.http.HttpStatus;

public class HttpHealthCheckedEndpointGroup extends HealthCheckedEndpointGroup {
    private final String healthCheckPath;

    /**
     * Creates a new instance.
     */
    public HttpHealthCheckedEndpointGroup(ClientFactory clientFactory,
                                          EndpointGroup delegate,
                                          long healthCheckRetryDelayMills,
                                          String healthCheckPath) {
        super(clientFactory, delegate, healthCheckRetryDelayMills);
        this.healthCheckPath = requireNonNull(healthCheckPath, "healthCheckPath");
    }

    /**
     * Creates a new instance.
     */
    public HttpHealthCheckedEndpointGroup(EndpointGroup delegate,
                                          String healthCheckPath,
                                          long healthCheckRetryDelayMills) {
        this(ClientFactory.DEFAULT, delegate, healthCheckRetryDelayMills, healthCheckPath);
    }

    @Override
    EndpointHealthChecker createEndpointHealthChecker(Endpoint endpoint) {
        return new HttpEndpointHealthChecker(clientFactory, endpoint, healthCheckPath);
    }

    private static final class HttpEndpointHealthChecker implements EndpointHealthChecker {

        private final String healthCheckPath;
        private final HttpClient httpClient;

        HttpEndpointHealthChecker(ClientFactory clientFactory,
                                  Endpoint endpoint,
                                  String healthCheckPath) {
            this.healthCheckPath = healthCheckPath;
            this.httpClient = Clients.newClient(clientFactory,
                                                "none+http://" + endpoint.authority(),
                                                HttpClient.class);
        }

        @Override
        public CompletableFuture<Boolean> isHealthy(Endpoint endpoint) {
            return httpClient.get(healthCheckPath)
                             .aggregate()
                             .thenApply(message -> message.status().equals(HttpStatus.OK));
        }
    }
}
