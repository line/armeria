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

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.dataloader.DataLoaderRegistry;

import com.google.common.collect.ImmutableList;

import graphql.GraphQL;
import graphql.execution.instrumentation.ChainedInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeVisitor;
import graphql.schema.SchemaTransformer;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.RuntimeWiring.Builder;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;

/**
 * Constructs a {@link GraphQLService} to serve GraphQL services from within Armeria.
 */
final class GraphQLServiceBuilder {

    private final ImmutableList.Builder<File> schemaFileBuilder = ImmutableList.builder();

    private final ImmutableList.Builder<RuntimeWiringConfigurator> runtimeWiringConfiguratorBuilder =
                                                                                        ImmutableList.builder();
    private final ImmutableList.Builder<GraphQLTypeVisitor> typeVisitorBuilder = ImmutableList.builder();
    private final ImmutableList.Builder<Instrumentation> instrumentationBuilder = ImmutableList.builder();
    private final ImmutableList.Builder<Consumer<GraphQL.Builder>> configurerBuilder = ImmutableList.builder();

    @Nullable
    private DataLoaderRegistry dataLoaderRegistry;

    GraphQLServiceBuilder() {}

    /**
     * Sets the schema file.
     * If not set, the `schema.graphql` or `schema.graphqls` will be imported from the resource.
     */
    public GraphQLServiceBuilder schemaFile(File... schemaFile) {
        return schemaFile(ImmutableList.copyOf(requireNonNull(schemaFile, "schemaFile")));
    }

    /**
     * Sets the schema file.
     * If not set, the `schema.graphql` or `schema.graphqls` will be imported from the resource.
     */
    public GraphQLServiceBuilder schemaFile(Iterable<File> schemaFile) {
        schemaFileBuilder.addAll(requireNonNull(schemaFile, "schemaFile"));
        return this;
    }

    /**
     * Sets the {@link DataLoaderRegistry}.
     */
    public GraphQLServiceBuilder dataLoaderRegistry(DataLoaderRegistry dataLoaderRegistry) {
        this.dataLoaderRegistry = dataLoaderRegistry;
        return this;
    }

    /**
     * Sets the {@link RuntimeWiringConfigurator}.
     */
    public GraphQLServiceBuilder runtimeWiring(RuntimeWiringConfigurator... runtimeWiringConfigurators) {
        final RuntimeWiringConfigurator[] configurators =
                requireNonNull(runtimeWiringConfigurators, "runtimeWiringConfigurators");
        return runtimeWiring(ImmutableList.copyOf(configurators));
    }

    /**
     * Sets the {@link RuntimeWiringConfigurator}.
     */
    public GraphQLServiceBuilder runtimeWiring(Iterable<RuntimeWiringConfigurator> configurators) {
        runtimeWiringConfiguratorBuilder.addAll(requireNonNull(configurators, "configurators"));
        return this;
    }

    /**
     * Sets the {@link GraphQLTypeVisitor}.
     */
    public GraphQLServiceBuilder typeVisitors(GraphQLTypeVisitor... typeVisitors) {
        return typeVisitors(ImmutableList.copyOf(requireNonNull(typeVisitors, "typeVisitors")));
    }

    /**
     * Sets the {@link GraphQLTypeVisitor}.
     */
    public GraphQLServiceBuilder typeVisitors(Iterable<GraphQLTypeVisitor> typeVisitors) {
        typeVisitorBuilder.addAll(requireNonNull(typeVisitors, "typeVisitors"));
        return this;
    }

    /**
     * Sets the {@link Instrumentation}.
     */
    public GraphQLServiceBuilder instrumentation(Instrumentation... instrumentations) {
        return instrumentation(ImmutableList.copyOf(requireNonNull(instrumentations, "instrumentations")));
    }

    /**
     * Sets the {@link Instrumentation}.
     */
    public GraphQLServiceBuilder instrumentation(Iterable<Instrumentation> instrumentations) {
        instrumentationBuilder.addAll(requireNonNull(instrumentations, "instrumentations"));
        return this;
    }

    /**
     * Sets the {@link GraphQL.Builder} consumer.
     */
    public GraphQLServiceBuilder configure(Consumer<GraphQL.Builder>... configurers) {
        return configure(ImmutableList.copyOf(requireNonNull(configurers, "configurers")));
    }

    /**
     * Sets the {@link GraphQL.Builder} consumer.
     */
    public GraphQLServiceBuilder configure(Iterable<Consumer<GraphQL.Builder>> configurers) {
        configurerBuilder.addAll(requireNonNull(configurers, "configurers"));
        return this;
    }

    /**
     * Creates a {@link GraphQLService}.
     */
    public GraphQLService build() {
        final TypeDefinitionRegistry registry = typeDefinitionRegistry(schemaFileBuilder.build());

        final RuntimeWiring runtimeWiring = buildRuntimeWiring(runtimeWiringConfiguratorBuilder.build());
        GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(registry, runtimeWiring);
        final List<GraphQLTypeVisitor> typeVisitors = typeVisitorBuilder.build();
        for (GraphQLTypeVisitor typeVisitor : typeVisitors) {
            schema = SchemaTransformer.transformSchema(schema, typeVisitor);
        }

        GraphQL.Builder builder = GraphQL.newGraphQL(schema);
        final List<Instrumentation> instrumentations = instrumentationBuilder.build();
        if (!instrumentations.isEmpty()) {
            builder = builder.instrumentation(new ChainedInstrumentation(instrumentations));
        }

        final List<Consumer<GraphQL.Builder>> configurers = configurerBuilder.build();
        for (Consumer<GraphQL.Builder> configurer : configurers) {
            configurer.accept(builder);
        }
        return new DefaultGraphQLService(builder.build(), dataLoaderRegistry);
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
        schemaFiles.forEach(it -> registry.merge(parser.parse(it)));
        return registry;
    }

    private static RuntimeWiring buildRuntimeWiring(
            List<RuntimeWiringConfigurator> runtimeWiringConfigurators) {
        final Builder runtimeWiringBuilder = RuntimeWiring.newRuntimeWiring();
        runtimeWiringConfigurators.forEach(it -> it.configure(runtimeWiringBuilder));
        return runtimeWiringBuilder.build();
    }

    private static List<File> defaultSchemaFiles() {
        final ClassLoader classLoader = ClassLoader.getSystemClassLoader();
        final ImmutableList.Builder<File> builder = ImmutableList.builder();
        resourcePath(classLoader, "schema.graphqls").ifPresent(it -> builder.add(toFile(it)));
        resourcePath(classLoader, "schema.graphql").ifPresent(it -> builder.add(toFile(it)));
        return builder.build();
    }

    private static Optional<URL> resourcePath(ClassLoader classLoader, String resourcePath) {
        return Optional.ofNullable(classLoader.getResource(resourcePath));
    }

    private static File toFile(URL it) {
        File f;
        try {
            f = new File(it.toURI());
        } catch (URISyntaxException ignored) {
            f = new File(it.getPath());
        }
        return f;
    }
}
