/*
 * Copyright 2022 LINE Corporation
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
package com.linecorp.armeria.common.scalar;

import java.nio.file.Path;

import com.linecorp.armeria.common.annotation.UnstableApi;

import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;

/**
 * A path scalar that converts a file path string into a {@link Path}.
 */
@UnstableApi
final class PathScalar {
    private static final GraphQLScalarType INSTANCE;

    /**
     * Returns the {@link GraphQLScalarType}.
     */
    static GraphQLScalarType of() {
        return INSTANCE;
    }

    static {
        final Coercing<Path, Void> coercing = new Coercing<Path, Void>() {
            @Override
            public Void serialize(Object dataFetcherResult)
                    throws CoercingSerializeException {
                throw new CoercingSerializeException("Path is an input-only type");
            }

            @Override
            public Path parseValue(Object input) throws CoercingParseValueException {
                if (input instanceof Path) {
                    return (Path) input;
                } else if (input == null) {
                    return null;
                } else {
                    throw new CoercingParseValueException(String.format("Expected type '%s' but was '%s'",
                                                                        Path.class, input.getClass()));
                }
            }

            @Override
            public Path parseLiteral(Object input) throws CoercingParseLiteralException {
                throw new CoercingParseLiteralException("Must use variables to specify Path values");
            }
        };

        INSTANCE = GraphQLScalarType.newScalar()
                                    .name("Path")
                                    .description("A file part in a multipart request")
                                    .coercing(coercing)
                                    .build();
    }

    private PathScalar() {}
}
