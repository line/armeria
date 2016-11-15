package com.linecorp.armeria.client.routing;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.linecorp.armeria.client.Endpoint;

final class OrElseEndpointGroup implements EndpointGroup {
    private final EndpointGroup first;
    private final EndpointGroup second;

    OrElseEndpointGroup(EndpointGroup first, EndpointGroup second) {
        this.first = requireNonNull(first, "first");
        this.second = requireNonNull(second, "second");
    }

    @Override
    public List<Endpoint> endpoints() {
        List<Endpoint> endpoints = first.endpoints();
        if (!endpoints.isEmpty()) {
            return endpoints;
        }
        return second.endpoints();
    }
}
