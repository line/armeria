package com.linecorp.armeria.server.dropwizard;

import javax.validation.Valid;

import com.fasterxml.jackson.annotation.JsonTypeName;

import io.dropwizard.jetty.ConnectorFactory;
import io.dropwizard.jetty.HttpConnectorFactory;

@JsonTypeName(ArmeriaHttpConnectorFactory.TYPE)
public class ArmeriaHttpConnectorFactory extends HttpConnectorFactory {
    public static final String TYPE = "armeria-http";

    @Valid
    public static ConnectorFactory build() {
        ArmeriaHttpConnectorFactory factory = new ArmeriaHttpConnectorFactory();
        factory.setPort(8082);
        return factory;
    }
}
