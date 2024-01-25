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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
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

import graphql.GraphQL;
import graphql.execution.ExecutionIdProvider;
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

    @Nullable
    private GraphQL graphql;

    // Fields for building a graphql
    @Nullable
    private ExecutionIdGenerator executionIdGenerator;
    @Nullable
    private ImmutableList.Builder<Instrumentation> instrumentations;
    @Nullable
    private ImmutableList.Builder<GraphqlConfigurator> graphqlBuilderConsumers;

    // Fields for building a schema in a graphql
    @Nullable
    private GraphQLSchema schema;
    @Nullable
    private ImmutableList.Builder<URL> schemaUrls;
    @Nullable
    private ImmutableList.Builder<RuntimeWiringConfigurator> runtimeWiringConfigurators;
    @Nullable
    private ImmutableList.Builder<GraphQLTypeVisitor> typeVisitors;

    // Fields for building a DataLoaderRegistry
    @Nullable
    private ImmutableList.Builder<Consumer<? super DataLoaderRegistry>> dataLoaderRegistryConsumers;
    @Nullable
    private Function<? super ServiceRequestContext, ? extends DataLoaderRegistry> dataLoaderRegistryFactory;

    // Others
    private boolean useBlockingTaskExecutor;
    @Nullable
    private GraphqlErrorHandler errorHandler;

    GraphqlServiceBuilder() {}

    /**
     * Sets the {@link GraphQL}.
     */
    public GraphqlServiceBuilder graphql(GraphQL graphql) {
        // TODO(minwoox): Consider deprecating setters for GraphQLSchema and DataLoaderRegistry so that users
        //                can just build GraphQL using its own builder.
        checkState(executionIdGenerator == null && instrumentations == null &&
                   graphqlBuilderConsumers == null && schema == null &&
                   schemaUrls == null && runtimeWiringConfigurators == null &&
                   typeVisitors == null &&
                   dataLoaderRegistryConsumers == null && dataLoaderRegistryFactory == null,
                   "graphql() and setting properties for a GraphQL are mutually exclusive.");
        this.graphql = requireNonNull(graphql, "graphql");
        return this;
    }

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
        checkState(graphql == null, "graphql() and schemaUrls() are mutually exclusive.");
        if (this.schemaUrls == null) {
            this.schemaUrls = ImmutableList.builder();
        }
        this.schemaUrls.addAll(requireNonNull(schemaUrls, "schemaUrls"));
        return this;
    }

    /**
     * Sets the {@link GraphQLSchema}.
     */
    public GraphqlServiceBuilder schema(GraphQLSchema schema) {
        checkState(graphql == null, "graphql() and schema() are mutually exclusive.");
        this.schema = requireNonNull(schema, "schema");
        return this;
    }

    /**
     * Sets {@link DataLoaderRegistry} creation function.
     */
    public GraphqlServiceBuilder dataLoaderRegistry(
            Function<? super ServiceRequestContext, ? extends DataLoaderRegistry> dataLoaderRegistryFactory) {
        checkState(graphql == null, "graphql() and dataLoaderRegistry() are mutually exclusive.");
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
        checkState(graphql == null, "graphql() and configureDataLoaderRegistry() are mutually exclusive.");
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
        checkState(graphql == null, "graphql() and runtimeWiring() are mutually exclusive.");
        if (runtimeWiringConfigurators == null) {
            runtimeWiringConfigurators = ImmutableList.builder();
        }
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
        checkState(graphql == null, "graphql() and typeVisitors() are mutually exclusive.");
        if (this.typeVisitors == null) {
            this.typeVisitors = ImmutableList.builder();
        }
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
        checkState(graphql == null, "graphql() and instrumentation() are mutually exclusive.");
        requireNonNull(instrumentations, "instrumentations");
        if (this.instrumentations == null) {
            this.instrumentations = ImmutableList.builder();
        }
        this.instrumentations.addAll(instrumentations);
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
        checkState(graphql == null, "graphql() and configureGraphql() are mutually exclusive.");
        requireNonNull(configurers, "configurers");
        if (graphqlBuilderConsumers == null) {
            graphqlBuilderConsumers = ImmutableList.builder();
        }
        graphqlBuilderConsumers.addAll(configurers);
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
        checkState(graphql == null, "graphql() and executionIdGenerator() are mutually exclusive.");
        this.executionIdGenerator = requireNonNull(executionIdGenerator, "executionIdGenerator");
        return this;
    }

    /**
     * Creates a {@link GraphqlService}.
     */
    public GraphqlService build() {
        final GraphQL graphql = buildGraphql();
        final Function<? super ServiceRequestContext, ? extends DataLoaderRegistry> dataLoaderRegistryFactory =
                buildDataLoaderRegistry();
        final GraphqlErrorHandler errorHandler;
        if (this.errorHandler == null) {
            errorHandler = GraphqlErrorHandler.of();
        } else {
            errorHandler = this.errorHandler.orElse(GraphqlErrorHandler.of());
        }
        return new DefaultGraphqlService(graphql,
                                         dataLoaderRegistryFactory,
                                         useBlockingTaskExecutor,
                                         errorHandler);
    }

    private GraphQL buildGraphql() {
        if (graphql != null) {
            return graphql;
        }
        final GraphQLSchema schema = buildSchema();
        final ExecutionIdProvider executionProvider =
                firstNonNull(executionIdGenerator, ExecutionIdGenerator.of()).asExecutionProvider();

        final GraphQL.Builder builder = GraphQL.newGraphQL(schema)
                                               .executionIdProvider(executionProvider);
        if (instrumentations != null) {
            final List<Instrumentation> instrumentations = this.instrumentations.build();
            builder.instrumentation(new ChainedInstrumentation(instrumentations));
        }
        if (graphqlBuilderConsumers != null) {
            final List<GraphqlConfigurator> graphqlBuilders = graphqlBuilderConsumers.build();
            for (GraphqlConfigurator configurer : graphqlBuilders) {
                configurer.configure(builder);
            }
        }
        return builder.build();
    }

    private GraphQLSchema buildSchema() {
        if (schema != null) {
            checkState(schemaUrls == null && runtimeWiringConfigurators == null &&
                       typeVisitors == null,
                       "Cannot add schemaUrl(or File), runtimeWiringConfigurator and " +
                       "typeVisitor when GraphqlSchema is specified.");
            return schema;
        }

        final TypeDefinitionRegistry registry = typeDefinitionRegistry();
        final RuntimeWiring runtimeWiring = buildRuntimeWiring();
        GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(registry, runtimeWiring);
        if (typeVisitors != null) {
            for (GraphQLTypeVisitor typeVisitor : typeVisitors.build()) {
                schema = SchemaTransformer.transformSchema(schema, typeVisitor);
            }
        }
        return schema;
    }

    private TypeDefinitionRegistry typeDefinitionRegistry() {
        final TypeDefinitionRegistry registry = new TypeDefinitionRegistry();
        final SchemaParser parser = new SchemaParser();
        final List<URL> schemaUrlList = schemaUrls != null ? schemaUrls.build() : defaultSchemaUrls();
        schemaUrlList.forEach(url -> {
            try (InputStream inputStream = url.openStream()) {
                registry.merge(parser.parse(inputStream));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return registry;
    }

    private RuntimeWiring buildRuntimeWiring() {
        final RuntimeWiring.Builder runtimeWiringBuilder = RuntimeWiring.newRuntimeWiring();
        if (runtimeWiringConfigurators != null) {
            runtimeWiringConfigurators.build().forEach(it -> it.configure(runtimeWiringBuilder));
        }
        return runtimeWiringBuilder.build();
    }

    private static List<URL> defaultSchemaUrls() {
        final ClassLoader classLoader = GraphqlServiceBuilder.class.getClassLoader();
        final List<URL> schemaFiles = DEFAULT_SCHEMA_FILE_NAMES
                .stream()
                .map(classLoader::getResource)
                .filter(Objects::nonNull)
                .collect(toImmutableList());

        if (schemaFiles.isEmpty()) {
            throw new IllegalStateException("Not found schema file(s)");
        }
        logger.info("Found schema files: {}", schemaFiles);
        return schemaFiles;
    }

    private Function<? super ServiceRequestContext, ? extends DataLoaderRegistry> buildDataLoaderRegistry() {
        final Function<? super ServiceRequestContext, ? extends DataLoaderRegistry> dataLoaderRegistryFactory;
        if (this.dataLoaderRegistryFactory != null) {
            dataLoaderRegistryFactory = this.dataLoaderRegistryFactory;
        } else if (dataLoaderRegistryConsumers != null) {
            final DataLoaderRegistry dataLoaderRegistry = new DataLoaderRegistry();
            for (Consumer<? super DataLoaderRegistry> configurer : dataLoaderRegistryConsumers.build()) {
                configurer.accept(dataLoaderRegistry);
            }
            dataLoaderRegistryFactory = ctx -> dataLoaderRegistry;
        } else {
            dataLoaderRegistryFactory = ctx -> new DataLoaderRegistry();
        }
        return dataLoaderRegistryFactory;
    }
}
