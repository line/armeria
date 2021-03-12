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
import java.util.function.Function;

import javax.annotation.Nullable;

import org.dataloader.DataLoaderRegistry;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.server.Route;

import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.RuntimeWiring.Builder;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;

/**
 * Constructs a {@link GraphQLService} to serve GraphQL services from within Armeria.
 */
final class GraphQLServiceBuilder {

    private static final String DEFAULT_PATH = "/graphql";

    private String path = DEFAULT_PATH;

    private final ImmutableList.Builder<File> schemaFileBuilder = ImmutableList.builder();

    private final ImmutableList.Builder<RuntimeWiringConfigurator> builder = ImmutableList.builder();

    @Nullable
    private DataLoaderRegistry dataLoaderRegistry;

    @Nullable
    private Function<? super GraphQLExecutor, ? extends GraphQLExecutor> decoratorFunction;

    GraphQLServiceBuilder() {}

    /**
     * Sets the path of the {@link GraphQLService}.
     * If not set, {@value DEFAULT_PATH} is used by default.
     */
    public GraphQLServiceBuilder path(String path) {
        this.path = requireNonNull(path, "path");
        return this;
    }

    /**
     * Sets the schema file.
     * If not set, the `schema.graphql` or `schema.graphqls` will be imported from the resource.
     */
    public GraphQLServiceBuilder schemaFile(File... schemaFiles) {
        return schemaFile(ImmutableList.copyOf(requireNonNull(schemaFiles, "schemaFiles")));
    }

    /**
     * Sets the schema file.
     * If not set, the `schema.graphql` or `schema.graphqls` will be imported from the resource.
     */
    public GraphQLServiceBuilder schemaFile(Iterable<File> schemaFiles) {
        schemaFileBuilder.addAll(requireNonNull(schemaFiles, "schemaFiles"));
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
    public GraphQLServiceBuilder runtimeWiring(RuntimeWiringConfigurator... configurators) {
        return runtimeWiring(ImmutableList.copyOf(requireNonNull(configurators, "configurators")));
    }

    /**
     * Sets the {@link RuntimeWiringConfigurator}.
     */
    public GraphQLServiceBuilder runtimeWiring(Iterable<RuntimeWiringConfigurator> configurators) {
        builder.addAll(requireNonNull(configurators, "configurators"));
        return this;
    }

    /**
     * Adds the specified GraphQL in/output {@code decorator}.
     */
    @SafeVarargs
    public final GraphQLServiceBuilder decorator(
            Function<? super GraphQLExecutor, ? extends GraphQLExecutor>... decorator) {
        return decorator(ImmutableList.copyOf(requireNonNull(decorator, "decorator")));
    }

    /**
     * Adds the specified GraphQL in/output {@code decorator}.
     */
    public GraphQLServiceBuilder decorator(
            Iterable<? extends Function<? super GraphQLExecutor, ? extends GraphQLExecutor>> decorator) {
        requireNonNull(decorator, "decorator");

        for (Function<? super GraphQLExecutor, ? extends GraphQLExecutor> it : decorator) {
            requireNonNull(it, "decorators contains null.");
            if (decoratorFunction != null) {
                decoratorFunction = decoratorFunction.andThen(it);
            } else {
                decoratorFunction = it;
            }
        }

        return this;
    }

    /**
     * Creates a {@link GraphQLService}.
     */
    public GraphQLService build() {
        final RuntimeWiring runtimeWiring = buildRuntimeWiring(builder.build());
        final SchemaParser parser = new SchemaParser();
        final TypeDefinitionRegistry registry = new TypeDefinitionRegistry();

        List<File> schemaFiles = schemaFileBuilder.build();
        if (schemaFiles.isEmpty()) {
            schemaFiles = defaultSchemaFiles();
        }
        if (schemaFiles.isEmpty()) {
            throw new IllegalStateException("Not found schema file(s)");
        }
        schemaFiles.forEach(it -> registry.merge(parser.parse(it)));

        final GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(registry, runtimeWiring);
        final GraphQL graphQL = GraphQL.newGraphQL(schema).build();
        final Route route = Route.builder().path(path).build();

        return new DefaultGraphQLService(graphQL, dataLoaderRegistry, route, decoratorFunction);
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
