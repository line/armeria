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
package com.linecorp.armeria.common.graphql.scalar;

import com.linecorp.armeria.common.multipart.MultipartFile;

import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;

/**
 * A multipart-file scalar that converts a file path string into a
 * {@link com.linecorp.armeria.common.multipart.MultipartFile}.
 */
final class MultipartFileScalar {

    static final GraphQLScalarType scalarType;

    static {
        final Coercing<MultipartFile, Void> coercing = new Coercing<MultipartFile, Void>() {
            @Override
            public Void serialize(Object dataFetcherResult)
                    throws CoercingSerializeException {
                throw new CoercingSerializeException("MultipartFile is an input-only type");
            }

            @Override
            public MultipartFile parseValue(Object input) throws CoercingParseValueException {
                if (input instanceof MultipartFile) {
                    return (MultipartFile) input;
                } else {
                    throw new CoercingParseValueException(String.format("Expected type '%s' but was '%s'",
                                                                        MultipartFile.class, input.getClass()));
                }
            }

            @Override
            public MultipartFile parseLiteral(Object input) throws CoercingParseLiteralException {
                throw new CoercingParseLiteralException("Must use variables to specify MultipartFile values");
            }
        };

        scalarType = GraphQLScalarType.newScalar()
                                      .name("MultipartFile")
                                      .description("A multipart-file in a multipart request")
                                      .coercing(coercing)
                                      .build();
    }

    private MultipartFileScalar() {
    }
}
