package com.linecorp.armeria.server.dropwizard;

import javax.validation.Valid;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;

import io.dropwizard.jetty.ConnectorFactory;
import io.dropwizard.jetty.HttpsConnectorFactory;

@JsonTypeName(ArmeriaHttpsConnectorFactory.TYPE)
public class ArmeriaHttpsConnectorFactory extends HttpsConnectorFactory {
    public static final String TYPE = "armeria-https";

    @JsonProperty
    private String keyCertChainFile;

    @JsonProperty
    private boolean selfSigned = false;

    public ArmeriaHttpsConnectorFactory() {
    }

    @Valid
    public static ConnectorFactory build() {
        ArmeriaHttpsConnectorFactory factory = new ArmeriaHttpsConnectorFactory();
        factory.setPort(8082);
        return factory;
    }

    public String getKeyCertChainFile() {
        return keyCertChainFile;
    }

    public void setKeyCertChainFile(final String keyCertChainFile) {
        this.keyCertChainFile = keyCertChainFile;
    }

    public boolean isSelfSigned() {
        return selfSigned;
    }

    public void setSelfSigned(final boolean selfSigned) {
        this.selfSigned = selfSigned;
    }
}
