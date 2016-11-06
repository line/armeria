package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.net.URI;

import com.google.common.base.MoreObjects;

/**
 * Default {@link ClientBuilderParams} implementation.
 */
public class DefaultClientBuilderParams implements ClientBuilderParams {

    private final ClientFactory factory;
    private final URI uri;
    private final Class<?> type;
    private final ClientOptions options;

    /**
     * Creates a new instance.
     */
    public DefaultClientBuilderParams(ClientFactory factory, URI uri, Class<?> type,
                                      ClientOptions options) {
        this.factory = requireNonNull(factory, "factory");
        this.uri = requireNonNull(uri, "uri");
        this.type = requireNonNull(type, "type");
        this.options = requireNonNull(options, "options");
    }

    @Override
    public ClientFactory factory() {
        return factory;
    }

    @Override
    public URI uri() {
        return uri;
    }

    @Override
    public Class<?> clientType() {
        return type;
    }

    @Override
    public ClientOptions options() {
        return options;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("factory", factory)
                          .add("uri", uri)
                          .add("type", type)
                          .add("options", options).toString();
    }
}
