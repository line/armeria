/*
 * Copyright 2021 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.graphql;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static graphql.com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

import org.dataloader.DataLoaderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.util.ResourceUtil;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.websocket.WebSocketServiceBuilder;

import graphql.GraphQL;
import graphql.execution.instrumentation.ChainedInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeVisitor;
import graphql.schema.SchemaTransformer;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;

/**
 * Constructs a {@link GraphqlService} to serve GraphQL within Armeria.
 */
@UnstableApi
public final class GraphqlServiceBuilder {

    private static final Logger logger = LoggerFactory.getLogger(GraphqlServiceBuilder.class);

    private static final List<String> DEFAULT_SCHEMA_FILE_NAMES = ImmutableList.of("schema.graphqls",
                                                                                   "schema.graphql");
    private final ImmutableList.Builder<URL> schemaUrls = ImmutableList.builder();

    private final ImmutableList.Builder<RuntimeWiringConfigurator> runtimeWiringConfigurators =
            ImmutableList.builder();
    private final ImmutableList.Builder<GraphQLTypeVisitor> typeVisitors = ImmutableList.builder();
    private final ImmutableList.Builder<Instrumentation> instrumentations = ImmutableList.builder();
    private final ImmutableList.Builder<GraphqlConfigurator> graphqlBuilderConsumers =
            ImmutableList.builder();

    @Nullable
    private ImmutableList.Builder<Consumer<? super DataLoaderRegistry>> dataLoaderRegistryConsumers;
    private boolean useBlockingTaskExecutor;

    @Nullable
    private Function<? super ServiceRequestContext, ? extends DataLoaderRegistry> dataLoaderRegistryFactory;

    @Nullable
    private GraphQLSchema schema;

    @Nullable
    private GraphqlErrorHandler errorHandler;

    private ExecutionIdGenerator executionIdGenerator = ExecutionIdGenerator.of();

    private boolean enableWebSocket;

    @Nullable
    private Consumer<WebSocketServiceBuilder> webSocketServiceCustomizer;

    GraphqlServiceBuilder() {}

    /**
     * Adds the schema {@link File}s.
     * If not set, the {@code schema.graphql} or {@code schema.graphqls} will be imported from the resource.
     */
    public GraphqlServiceBuilder schemaFile(File... schemaFiles) {
        return schemaFile(ImmutableList.copyOf(requireNonNull(schemaFiles, "schemaFiles")));
    }

    /**
     * Adds the schema {@link File}s.
     * If not set, the {@code schema.graphql} or {@code schema.graphqls} will be imported from the resource.
     */
    public GraphqlServiceBuilder schemaFile(Iterable<? extends File> schemaFiles) {
        requireNonNull(schemaFiles, "schemaFiles");
        return schemaUrls0(Streams.stream(schemaFiles)
                                  .map(file -> {
                                      try {
                                          return file.toURI().toURL();
                                      } catch (MalformedURLException e) {
                                          throw new UncheckedIOException(e);
                                      }
                                  }).collect(toImmutableList()));
    }

    /**
     * Adds the schema loaded from the given URLs.
     * If not set, the {@code schema.graphql} or {@code schema.graphqls} will be imported from the resource.
     */
    public GraphqlServiceBuilder schemaUrls(String... schemaUrls) {
        return schemaUrls(ImmutableList.copyOf(requireNonNull(schemaUrls, "schemaUrls")));
    }

    /**
     * Adds the schema loaded from the given URLs.
     * If not set, the {@code schema.graphql} or {@code schema.graphqls} will be imported from the resource.
     */
    public GraphqlServiceBuilder schemaUrls(Iterable<String> schemaUrls) {
        requireNonNull(schemaUrls, "schemaUrls");
        return schemaUrls0(Streams.stream(schemaUrls)
                                  .map(url -> {
                                      try {
                                          return ResourceUtil.getUrl(url);
                                      } catch (FileNotFoundException e) {
                                          throw new IllegalStateException("Not found schema file(s)", e);
                                      }
                                  }).collect(toImmutableList()));
    }

    private GraphqlServiceBuilder schemaUrls0(Iterable<URL> schemaUrls) {
        this.schemaUrls.addAll(requireNonNull(schemaUrls, "schemaUrls"));
        return this;
    }

    /**
     * Sets the {@link GraphQLSchema}.
     */
    public GraphqlServiceBuilder schema(GraphQLSchema schema) {
        this.schema = requireNonNull(schema, "schema");
        return this;
    }

    /**
     * Sets {@link DataLoaderRegistry} creation function.
     */
    public GraphqlServiceBuilder dataLoaderRegistry(
            Function<? super ServiceRequestContext, ? extends DataLoaderRegistry> dataLoaderRegistryFactory) {
        checkState(dataLoaderRegistryConsumers == null,
                   "configureDataLoaderRegistry() and dataLoaderRegistry() are mutually exclusive.");
        this.dataLoaderRegistryFactory =
                requireNonNull(dataLoaderRegistryFactory, "dataLoaderRegistryFactory");
        return this;
    }

    /**
     * Adds the {@link DataLoaderRegistry} consumers.
     *
     * @deprecated Use {@link #dataLoaderRegistry(Function)} instead.
     */
    @Deprecated
    public GraphqlServiceBuilder configureDataLoaderRegistry(Consumer<DataLoaderRegistry>... configurers) {
        requireNonNull(configurers, "configurers");
        return configureDataLoaderRegistry(ImmutableList.copyOf(configurers));
    }

    /**
     * Adds the {@link DataLoaderRegistry} consumers.
     *
     * @deprecated Use {@link #dataLoaderRegistry(Function)} instead.
     */
    @Deprecated
    public GraphqlServiceBuilder configureDataLoaderRegistry(
            Iterable<? extends Consumer<? super DataLoaderRegistry>> configurers) {
        checkState(dataLoaderRegistryFactory == null,
                   "configureDataLoaderRegistry() and dataLoaderRegistry() are mutually exclusive.");
        if (dataLoaderRegistryConsumers == null) {
            dataLoaderRegistryConsumers = ImmutableList.builder();
        }
        dataLoaderRegistryConsumers.addAll(requireNonNull(configurers, "configurers"));
        return this;
    }

    /**
     * Adds the {@link RuntimeWiringConfigurator}s.
     */
    public GraphqlServiceBuilder runtimeWiring(RuntimeWiringConfigurator... runtimeWiringConfigurators) {
        requireNonNull(runtimeWiringConfigurators, "runtimeWiringConfigurators");
        return runtimeWiring(ImmutableList.copyOf(runtimeWiringConfigurators));
    }

    /**
     * Adds the {@link RuntimeWiringConfigurator}s.
     */
    public GraphqlServiceBuilder runtimeWiring(Iterable<? extends RuntimeWiringConfigurator> configurators) {
        runtimeWiringConfigurators.addAll(requireNonNull(configurators, "configurators"));
        return this;
    }

    /**
     * Adds the {@link GraphQLTypeVisitor}s.
     */
    public GraphqlServiceBuilder typeVisitors(GraphQLTypeVisitor... typeVisitors) {
        return typeVisitors(ImmutableList.copyOf(requireNonNull(typeVisitors, "typeVisitors")));
    }

    /**
     * Adds the {@link GraphQLTypeVisitor}s.
     */
    public GraphqlServiceBuilder typeVisitors(Iterable<? extends GraphQLTypeVisitor> typeVisitors) {
        this.typeVisitors.addAll(requireNonNull(typeVisitors, "typeVisitors"));
        return this;
    }

    /**
     * Adds the {@link Instrumentation}s.
     */
    public GraphqlServiceBuilder instrumentation(Instrumentation... instrumentations) {
        requireNonNull(instrumentations, "instrumentations");
        return instrumentation(ImmutableList.copyOf(instrumentations));
    }

    /**
     * Adds the {@link Instrumentation}s.
     */
    public GraphqlServiceBuilder instrumentation(Iterable<? extends Instrumentation> instrumentations) {
        this.instrumentations.addAll(requireNonNull(instrumentations, "instrumentations"));
        return this;
    }

    /**
     * Adds the {@link GraphqlConfigurator} consumers.
     */
    public GraphqlServiceBuilder configureGraphql(GraphqlConfigurator... configurers) {
        return configureGraphql(ImmutableList.copyOf(requireNonNull(configurers, "configurers")));
    }

    /**
     * Adds the {@link GraphqlConfigurator} consumers.
     */
    public GraphqlServiceBuilder configureGraphql(
            Iterable<? extends GraphqlConfigurator> configurers) {
        graphqlBuilderConsumers.addAll(requireNonNull(configurers, "configurers"));
        return this;
    }

    /**
     * Sets whether the service executes service methods using the blocking executor.
     */
    public GraphqlServiceBuilder useBlockingTaskExecutor(boolean useBlockingTaskExecutor) {
        this.useBlockingTaskExecutor = useBlockingTaskExecutor;
        return this;
    }

    /**
     * Enables <a href="https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md">GraphQL over WebSocket Protocol</a>.
     */
    public GraphqlServiceBuilder enableWebSocket(boolean enableWebSocket) {
        this.enableWebSocket = enableWebSocket;
        return this;
    }

    /**
     * Sets an optional {@link WebSocketServiceBuilder} customizer.
     */
    public GraphqlServiceBuilder webSocketServiceCustomizer(
            Consumer<WebSocketServiceBuilder> webSocketServiceCustomizer) {
        requireNonNull(webSocketServiceCustomizer, "webSocketServiceCustomizer");
        this.webSocketServiceCustomizer = webSocketServiceCustomizer;
        return this;
    }

    /**
     * Adds the {@link GraphqlErrorHandler}. If multiple handlers are added, the latter is composed with the
     * former one using {@link GraphqlErrorHandler#orElse(GraphqlErrorHandler)}.
     *
     * <p>If not specified, {@link GraphqlErrorHandler#of()} is used by default.
     */
    public GraphqlServiceBuilder errorHandler(GraphqlErrorHandler errorHandler) {
        requireNonNull(errorHandler, "errorHandler");
        if (this.errorHandler == null) {
            this.errorHandler = errorHandler;
        } else {
            this.errorHandler = this.errorHandler.orElse(errorHandler);
        }
        return this;
    }

    /**
     * Sets the {@link ExecutionIdGenerator}.
     * If not specified, {@link ExecutionIdGenerator#of()} is used by default.
     */
    public GraphqlServiceBuilder executionIdGenerator(ExecutionIdGenerator executionIdGenerator) {
        this.executionIdGenerator = requireNonNull(executionIdGenerator, "executionIdGenerator");
        return this;
    }

    /**
     * Creates a {@link GraphqlService}.
     */
    public GraphqlService build() {
        checkArgument(enableWebSocket || webSocketServiceCustomizer == null,
                      "enableWebSocket must be true to customize WebSocketServiceBuilder");

        final GraphQLSchema schema = buildSchema();
        GraphQL.Builder builder = GraphQL.newGraphQL(schema)
                                         .executionIdProvider(executionIdGenerator.asExecutionProvider());
        final List<Instrumentation> instrumentations = this.instrumentations.build();
        if (!instrumentations.isEmpty()) {
            builder = builder.instrumentation(new ChainedInstrumentation(instrumentations));
        }

        final List<GraphqlConfigurator> graphqlBuilders = graphqlBuilderConsumers.build();
        for (GraphqlConfigurator configurer : graphqlBuilders) {
            configurer.configure(builder);
        }

        Function<? super ServiceRequestContext, ? extends DataLoaderRegistry> dataLoaderRegistryFactory = null;
        if (this.dataLoaderRegistryFactory != null) {
            dataLoaderRegistryFactory = this.dataLoaderRegistryFactory;
        } else if (dataLoaderRegistryConsumers != null) {
            final DataLoaderRegistry dataLoaderRegistry = new DataLoaderRegistry();
            for (Consumer<? super DataLoaderRegistry> configurer : dataLoaderRegistryConsumers.build()) {
                configurer.accept(dataLoaderRegistry);
            }
            dataLoaderRegistryFactory = ctx -> dataLoaderRegistry;
        } else {
            assert dataLoaderRegistryFactory == null && dataLoaderRegistryConsumers == null;
            dataLoaderRegistryFactory = ctx -> new DataLoaderRegistry();
        }

        final GraphqlErrorHandler errorHandler;
        if (this.errorHandler == null) {
            errorHandler = GraphqlErrorHandler.of();
        } else {
            errorHandler = this.errorHandler.orElse(GraphqlErrorHandler.of());
        }

        final DefaultGraphqlService graphqlService = new DefaultGraphqlService(builder.build(),
                                                                               dataLoaderRegistryFactory,
                                                                               useBlockingTaskExecutor,
                                                                               errorHandler);
        if (enableWebSocket) {
            return new GraphqlWebSocketService(graphqlService, dataLoaderRegistryFactory,
                                               webSocketServiceCustomizer);
        } else {
            return graphqlService;
        }
    }

    private GraphQLSchema buildSchema() {
        final List<URL> schemaUrls = this.schemaUrls.build();
        final List<RuntimeWiringConfigurator> runtimeWiringConfigurators =
                this.runtimeWiringConfigurators.build();
        final List<GraphQLTypeVisitor> typeVisitors = this.typeVisitors.build();

        if (schema != null) {
            checkState(schemaUrls.isEmpty() && runtimeWiringConfigurators.isEmpty() &&
                       typeVisitors.isEmpty(),
                       "Cannot add schemaUrl(or File), runtimeWiringConfigurator and " +
                       "typeVisitor when GraphqlSchema is specified.");
            return schema;
        }

        final TypeDefinitionRegistry registry = typeDefinitionRegistry(schemaUrls);
        final RuntimeWiring runtimeWiring = buildRuntimeWiring(runtimeWiringConfigurators);
        GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(registry, runtimeWiring);
        for (GraphQLTypeVisitor typeVisitor : typeVisitors) {
            schema = SchemaTransformer.transformSchema(schema, typeVisitor);
        }
        return schema;
    }

    private static TypeDefinitionRegistry typeDefinitionRegistry(List<URL> schemaUrls) {
        final TypeDefinitionRegistry registry = new TypeDefinitionRegistry();
        final SchemaParser parser = new SchemaParser();
        if (schemaUrls.isEmpty()) {
            schemaUrls = defaultSchemaUrls();
        }
        if (schemaUrls.isEmpty()) {
            throw new IllegalStateException("Not found schema file(s)");
        }

        logger.info("Found schema files: {}", schemaUrls);
        schemaUrls.forEach(url -> {
            try (InputStream inputStream = url.openStream()) {
                registry.merge(parser.parse(inputStream));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return registry;
    }

    private static RuntimeWiring buildRuntimeWiring(
            List<RuntimeWiringConfigurator> runtimeWiringConfigurators) {
        final RuntimeWiring.Builder runtimeWiringBuilder = RuntimeWiring.newRuntimeWiring();
        runtimeWiringConfigurators.forEach(it -> it.configure(runtimeWiringBuilder));
        return runtimeWiringBuilder.build();
    }

    private static List<URL> defaultSchemaUrls() {
        final ClassLoader classLoader = GraphqlServiceBuilder.class.getClassLoader();
        return DEFAULT_SCHEMA_FILE_NAMES
                .stream()
                .map(classLoader::getResource)
                .filter(Objects::nonNull)
                .collect(toImmutableList());
    }
}
