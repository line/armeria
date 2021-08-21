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

package com.linecorp.armeria.server.grpc;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.util.StringUtil;
import com.linecorp.armeria.server.grpc.GrpcTranscodingPathParser.PathSegment.PathMappingType;

/**
 * Parses HTTP API path defined in {@code google.api.http} option.
 */
final class GrpcTranscodingPathParser {

    /**
     * Parses HTTP API path defined in {@code google.api.http} option.
     *
     * <p>
     * Template = "/" Segments [ Verb ]
     * Verb     = ":" LITERAL
     * </p>
     */
    static List<PathSegment> parse(String path) {
        checkArgument(!Strings.isNullOrEmpty(path), "Invalid path: path=%s", path);
        final Context context = new Context(path);
        final ImmutableList.Builder<PathSegment> segments = ImmutableList.builder();
        segments.addAll(parseSegments(context, new Delimiters(':')));
        if (context.hasNext()) {
            checkArgument(context.read() == ':', "Invalid path: context=%s", context);
            segments.add(new VerbPathSegment(context.readAll()));
        }
        return segments.build();
    }

    /**
     * Parses 'Segments' part.
     *
     * <p>
     * Segments = Segment { "/" Segment }
     * </p>
     */
    private static List<PathSegment> parseSegments(Context context, Delimiters delimiters) {
        final ImmutableList.Builder<PathSegment> segments = ImmutableList.builder();
        while (context.hasNext()) {
            segments.add(parseSegment(context, delimiters.withMoreCharacter('/')));

            if (!context.hasNext()) {
                return segments.build();
            }

            final char c = context.peek();
            if (delimiters.contains(c)) {
                return segments.build();
            }

            // Consume '/' and parse next segment.
            checkArgument(context.read() == '/', "Invalid path: context=%s", context);
        }
        return segments.build();
    }

    /**
     * Parses 'Segment' part.
     *
     * <p>
     * Segment  = "*" | "**" | LITERAL | Variable
     * </p>
     */
    private static PathSegment parseSegment(Context context, Delimiters delimiters) {
        final char firstCh = context.read();
        switch (firstCh) {
            case '{': {
                final Delimiters variableStopBefore = new Delimiters('}');
                final PathSegment segment = parseVariable(context, variableStopBefore);
                checkArgument(variableStopBefore.contains(context.read()), "Invalid path: context=%s", context);
                return segment;
            }
            case '*': {
                final PathSegment segment;
                if (context.peek() == '*') {
                    context.read();
                    segment = new DeepWildcardPathSegment(context.nextPathVarIndex());
                } else {
                    segment = new WildcardPathSegment(context.nextPathVarIndex(), null);
                }
                checkArgument(!context.hasNext() || delimiters.contains(context.peek()),
                              "Invalid path: context=%s", context);
                return segment;
            }
            default: {
                final StringBuilder literalBuilder = new StringBuilder().append(firstCh);
                while (context.hasNext()) {
                    final char c = context.peek();
                    if (delimiters.contains(c)) {
                        break;
                    }
                    literalBuilder.append(context.read());
                }
                return new LiteralPathSegment(literalBuilder.toString());
            }
        }
    }

    /**
     * Parses 'Variable' part.
     *
     * <p>
     * Variable = "{" FieldPath [ "=" Segments ] "}"
     * </p>
     */
    private static PathSegment parseVariable(Context context, Delimiters delimiters) {
        final StringBuilder fieldPathBuilder = new StringBuilder();
        char c = 0;
        while (context.hasNext()) {
            c = context.peek();
            if (delimiters.contains(c) || c == '=') {
                break;
            }
            fieldPathBuilder.append(context.read());
        }

        final String fieldPath = fieldPathBuilder.toString();
        checkArgument(!fieldPath.isEmpty(), "Invalid path: context=%s", context);

        if (c == '=') {
            // Consume '='.
            context.read();

            final List<PathSegment> segments = parseSegments(context, delimiters);

            // Replace a single WildcardPathSegment with a new one which has a field name
            // as its identifier in order to make the path variable human-readable.
            if (segments.size() == 1 && segments.get(0) instanceof WildcardPathSegment) {
                return new VariablePathSegment(
                        fieldPath, ImmutableList.of(((WildcardPathSegment) segments.get(0))
                                                            .withParentFieldPath(fieldPath)));
            } else {
                return new VariablePathSegment(fieldPath, segments);
            }
        } else {
            // Treat '{name}' as '{name=*}' to specify a path variable index.
            return new VariablePathSegment(fieldPath,
                                           ImmutableList.of(new WildcardPathSegment(context.nextPathVarIndex(),
                                                                                    fieldPath)));
        }
    }

    private GrpcTranscodingPathParser() {}

    static final class Context {
        private final String path;
        private int index;
        private int pathVarIndex;

        Context(String path) {
            this.path = path;
        }

        public int nextPathVarIndex() {
            return pathVarIndex++;
        }

        boolean hasNext() {
            return index < path.length();
        }

        char read() {
            return hasNext() ? path.charAt(index++) : (char) -1;
        }

        String readAll() {
            final String read = path.substring(index);
            index = path.length();
            return read;
        }

        char peek() {
            return path.charAt(index);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("path", path)
                              .add("index", index)
                              .add("pathVarIndex", pathVarIndex)
                              .toString();
        }
    }

    static final class Delimiters {
        private final Set<Integer> characters;

        Delimiters(int... characters) {
            this.characters = Arrays.stream(characters).boxed().collect(toImmutableSet());
        }

        private Delimiters(Set<Integer> characters) {
            this.characters = characters;
        }

        boolean contains(int ch) {
            return characters.contains(ch);
        }

        Delimiters withMoreCharacter(int ch) {
            return new Delimiters(ImmutableSet.<Integer>builder().addAll(characters).add(ch).build());
        }
    }

    interface PathSegment {
        /**
         * Returns a segment string which would be a part of path pattern.
         */
        String segmentString(PathMappingType type);

        /**
         * Returns the name of a path variable.
         */
        @Nullable
        default String pathVariable(PathMappingType type) {
            return null;
        }

        /**
         * Returns whether the specified {@link PathMappingType} is supported by
         * this {@link PathSegment}.
         */
        default boolean support(PathMappingType type) {
            return true;
        }

        enum PathMappingType {
            PARAMETERIZED, GLOB
        }
    }

    static class LiteralPathSegment implements PathSegment {
        private final String literal;

        LiteralPathSegment(String literal) {
            this.literal = literal;
        }

        @Override
        public String segmentString(PathMappingType type) {
            return literal;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("literal", literal)
                              .toString();
        }
    }

    static class VerbPathSegment implements PathSegment {
        private final String verb;

        VerbPathSegment(String verb) {
            this.verb = verb;
        }

        @Override
        public String segmentString(PathMappingType type) {
            return verb;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("verb", verb)
                              .toString();
        }
    }

    static class WildcardPathSegment implements PathSegment {
        private final int pathVarIndex;
        @Nullable
        private final String parentFieldPath;

        WildcardPathSegment(int pathVarIndex, @Nullable String parentFieldPath) {
            this.pathVarIndex = pathVarIndex;
            this.parentFieldPath = parentFieldPath;
        }

        @Override
        public String segmentString(PathMappingType type) {
            return type == PathMappingType.PARAMETERIZED ? ':' + pathVariable(type)
                                                         : "*";
        }

        @Override
        public String pathVariable(PathMappingType type) {
            if (type == PathMappingType.PARAMETERIZED) {
                if (parentFieldPath != null) {
                    return parentFieldPath;
                } else {
                    return 'p' + StringUtil.toString(pathVarIndex);
                }
            } else {
                return StringUtil.toString(pathVarIndex);
            }
        }

        WildcardPathSegment withParentFieldPath(String parentFieldPath) {
            return new WildcardPathSegment(pathVarIndex, parentFieldPath);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("pathVarIndex", pathVarIndex)
                              .add("parentFieldPath", parentFieldPath)
                              .toString();
        }
    }

    static class DeepWildcardPathSegment implements PathSegment {
        private final int pathVarIndex;

        DeepWildcardPathSegment(int pathVarIndex) {
            this.pathVarIndex = pathVarIndex;
        }

        @Override
        public String segmentString(PathMappingType type) {
            if (type == PathMappingType.PARAMETERIZED) {
                throw new UnsupportedOperationException("Unable to convert to ParameterizedPathMapping.");
            }
            return "**";
        }

        @Override
        public String pathVariable(PathMappingType type) {
            return type == PathMappingType.GLOB ? StringUtil.toString(pathVarIndex)
                                                : null;
        }

        @Override
        public boolean support(PathMappingType type) {
            // '**' is only supported by glob pattern.
            return type == PathMappingType.GLOB;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("pathVarIndex", pathVarIndex)
                              .toString();
        }
    }

    static class VariablePathSegment implements PathSegment {
        private final String fieldPath; // gRPC field name
        private final List<PathSegment> valueSegments;

        VariablePathSegment(String fieldPath, List<PathSegment> valueSegments) {
            this.fieldPath = fieldPath;
            this.valueSegments = valueSegments;
        }

        public String fieldPath() {
            return fieldPath;
        }

        public List<PathSegment> valueSegments() {
            return valueSegments;
        }

        @Override
        public String segmentString(PathMappingType type) {
            return type == PathMappingType.PARAMETERIZED ? Stringifier.asParameterizedPath(valueSegments)
                                                         : Stringifier.asGlobPath(valueSegments);
        }

        @Override
        public boolean support(PathMappingType type) {
            return valueSegments.stream().allMatch(segment -> segment.support(type));
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("fieldPath", fieldPath)
                              .add("valueSegments", valueSegments)
                              .toString();
        }
    }

    static final class Stringifier {
        /**
         * Returns a parameterized path string of the parsed {@link PathSegment}s.
         */
        static String asParameterizedPath(List<PathSegment> segments) {
            requireNonNull(segments, "segments");
            return segments.stream().map(segment -> segment.segmentString(PathMappingType.PARAMETERIZED))
                           .collect(Collectors.joining("/"));
        }

        /**
         * Returns a glob path string of the parsed {@link PathSegment}s.
         */
        static String asGlobPath(List<PathSegment> segments) {
            requireNonNull(segments, "segments");
            return segments.stream().map(segment -> segment.segmentString(PathMappingType.GLOB))
                           .collect(Collectors.joining("/"));
        }

        private Stringifier() {
        }
    }
}
