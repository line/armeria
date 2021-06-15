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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.dataloader.DataLoaderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.UnstableApi;

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
 * Constructs a {@link GraphQLService} to serve GraphQL within Armeria.
 */
@UnstableApi
public final class GraphQLServiceBuilder {

    private static final Logger logger = LoggerFactory.getLogger(GraphQLServiceBuilder.class);

    private static final List<String> DEFAULT_SCHEMA_FILE_NAMES = ImmutableList.of("schema.graphqls",
                                                                                   "schema.graphql");
    private final ImmutableList.Builder<File> schemaFiles = ImmutableList.builder();

    private final ImmutableList.Builder<RuntimeWiringConfigurator> runtimeWiringConfigurators =
            ImmutableList.builder();
    private final ImmutableList.Builder<Consumer<? super DataLoaderRegistry>> dataLoaderRegistryConsumers =
            ImmutableList.builder();
    private final ImmutableList.Builder<GraphQLTypeVisitor> typeVisitors = ImmutableList.builder();
    private final ImmutableList.Builder<Instrumentation> instrumentations = ImmutableList.builder();
    private final ImmutableList.Builder<GraphQLConfigurator> graphQLBuilderConsumers =
            ImmutableList.builder();

    private boolean useBlockingTaskExecutor;

    GraphQLServiceBuilder() {}

    /**
     * Adds the schema {@link File}s.
     * If not set, the {@code schema.graphql} or {@code schema.graphqls} will be imported from the resource.
     */
    public GraphQLServiceBuilder schemaFile(File... schemaFiles) {
        return schemaFile(ImmutableList.copyOf(requireNonNull(schemaFiles, "schemaFiles")));
    }

    /**
     * Adds the schema {@link File}s.
     * If not set, the {@code schema.graphql} or {@code schema.graphqls} will be imported from the resource.
     */
    public GraphQLServiceBuilder schemaFile(Iterable<? extends File> schemaFiles) {
        this.schemaFiles.addAll(requireNonNull(schemaFiles, "schemaFiles"));
        return this;
    }

    /**
     * Adds the {@link DataLoaderRegistry} consumers.
     */
    public GraphQLServiceBuilder configureDataLoaderRegistry(Consumer<DataLoaderRegistry>... configurers) {
        requireNonNull(configurers, "configurers");
        return configureDataLoaderRegistry(ImmutableList.copyOf(configurers));
    }

    /**
     * Adds the {@link DataLoaderRegistry} consumers.
     */
    public GraphQLServiceBuilder configureDataLoaderRegistry(
            Iterable<? extends Consumer<? super DataLoaderRegistry>> configurers) {
        dataLoaderRegistryConsumers.addAll(requireNonNull(configurers, "configurers"));
        return this;
    }

    /**
     * Adds the {@link RuntimeWiringConfigurator}s.
     */
    public GraphQLServiceBuilder runtimeWiring(RuntimeWiringConfigurator... runtimeWiringConfigurators) {
        requireNonNull(runtimeWiringConfigurators, "runtimeWiringConfigurators");
        return runtimeWiring(ImmutableList.copyOf(runtimeWiringConfigurators));
    }

    /**
     * Adds the {@link RuntimeWiringConfigurator}s.
     */
    public GraphQLServiceBuilder runtimeWiring(Iterable<? extends RuntimeWiringConfigurator> configurators) {
        runtimeWiringConfigurators.addAll(requireNonNull(configurators, "configurators"));
        return this;
    }

    /**
     * Adds the {@link GraphQLTypeVisitor}s.
     */
    public GraphQLServiceBuilder typeVisitors(GraphQLTypeVisitor... typeVisitors) {
        return typeVisitors(ImmutableList.copyOf(requireNonNull(typeVisitors, "typeVisitors")));
    }

    /**
     * Adds the {@link GraphQLTypeVisitor}s.
     */
    public GraphQLServiceBuilder typeVisitors(Iterable<? extends GraphQLTypeVisitor> typeVisitors) {
        this.typeVisitors.addAll(requireNonNull(typeVisitors, "typeVisitors"));
        return this;
    }

    /**
     * Adds the {@link Instrumentation}s.
     */
    public GraphQLServiceBuilder instrumentation(Instrumentation... instrumentations) {
        requireNonNull(instrumentations, "instrumentations");
        return instrumentation(ImmutableList.copyOf(instrumentations));
    }

    /**
     * Adds the {@link Instrumentation}s.
     */
    public GraphQLServiceBuilder instrumentation(Iterable<? extends Instrumentation> instrumentations) {
        this.instrumentations.addAll(requireNonNull(instrumentations, "instrumentations"));
        return this;
    }

    /**
     * Adds the {@link GraphQLConfigurator} consumers.
     */
    public GraphQLServiceBuilder configureGraphQL(GraphQLConfigurator... configurers) {
        return configureGraphQL(ImmutableList.copyOf(requireNonNull(configurers, "configurers")));
    }

    /**
     * Adds the {@link GraphQLConfigurator} consumers.
     */
    public GraphQLServiceBuilder configureGraphQL(
            Iterable<? extends GraphQLConfigurator> configurers) {
        graphQLBuilderConsumers.addAll(requireNonNull(configurers, "configurers"));
        return this;
    }

    /**
     * Sets whether the service executes service methods using the blocking executor.
     */
    public GraphQLServiceBuilder useBlockingTaskExecutor(boolean useBlockingTaskExecutor) {
        this.useBlockingTaskExecutor = useBlockingTaskExecutor;
        return this;
    }

    /**
     * Creates a {@link GraphQLService}.
     */
    public GraphQLService build() {
        final TypeDefinitionRegistry registry = typeDefinitionRegistry(schemaFiles.build());

        final RuntimeWiring runtimeWiring = buildRuntimeWiring(runtimeWiringConfigurators.build());
        GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(registry, runtimeWiring);
        final List<GraphQLTypeVisitor> typeVisitors = this.typeVisitors.build();
        for (GraphQLTypeVisitor typeVisitor : typeVisitors) {
            schema = SchemaTransformer.transformSchema(schema, typeVisitor);
        }

        GraphQL.Builder builder = GraphQL.newGraphQL(schema);
        final List<Instrumentation> instrumentations = this.instrumentations.build();
        if (!instrumentations.isEmpty()) {
            builder = builder.instrumentation(new ChainedInstrumentation(instrumentations));
        }

        final List<GraphQLConfigurator> graphQLBuilders = graphQLBuilderConsumers.build();
        for (GraphQLConfigurator configurer : graphQLBuilders) {
            configurer.configure(builder);
        }

        final DataLoaderRegistry dataLoaderRegistry = new DataLoaderRegistry();
        final List<Consumer<? super DataLoaderRegistry>> dataLoaderRegistries =
                dataLoaderRegistryConsumers.build();
        for (Consumer<? super DataLoaderRegistry> configurer : dataLoaderRegistries) {
            configurer.accept(dataLoaderRegistry);
        }
        return new DefaultGraphQLService(builder.build(), dataLoaderRegistry, useBlockingTaskExecutor);
    }

    private static TypeDefinitionRegistry typeDefinitionRegistry(List<File> schemaFiles) {
        final TypeDefinitionRegistry registry = new TypeDefinitionRegistry();
        final SchemaParser parser = new SchemaParser();
        if (schemaFiles.isEmpty()) {
            schemaFiles = defaultSchemaFiles();
        }
        if (schemaFiles.isEmpty()) {
            throw new IllegalStateException("Not found schema file(s)");
        }

        logger.info("Found schema files: {}", schemaFiles);
        schemaFiles.forEach(file -> registry.merge(parser.parse(file)));
        return registry;
    }

    private static RuntimeWiring buildRuntimeWiring(
            List<RuntimeWiringConfigurator> runtimeWiringConfigurators) {
        final RuntimeWiring.Builder runtimeWiringBuilder = RuntimeWiring.newRuntimeWiring();
        runtimeWiringConfigurators.forEach(it -> it.configure(runtimeWiringBuilder));
        return runtimeWiringBuilder.build();
    }

    private static List<File> defaultSchemaFiles() {
        final ClassLoader classLoader = GraphQLServiceBuilder.class.getClassLoader();
        return DEFAULT_SCHEMA_FILE_NAMES
                .stream()
                .map(classLoader::getResource)
                .filter(Objects::nonNull)
                .map(GraphQLServiceBuilder::toFile)
                .collect(toImmutableList());
    }

    private static File toFile(URL url) {
        try {
            return new File(url.toURI());
        } catch (URISyntaxException ignored) {
            return new File(url.getPath());
        }
    }
}
