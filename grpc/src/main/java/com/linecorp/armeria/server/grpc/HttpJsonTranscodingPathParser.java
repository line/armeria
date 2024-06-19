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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.util.StringUtil;
import com.linecorp.armeria.server.grpc.HttpJsonTranscodingPathParser.PathSegment.PathMappingType;

/**
 * Parses HTTP API path defined in {@code google.api.http} option.
 */
final class HttpJsonTranscodingPathParser {

    /**
     * Parses HTTP API path defined in {@code google.api.http} option.
     *
     * <p>
     * Template = "/" Segments [ Verb ]
     * Verb     = ":" LITERAL
     * </p>
     *
     * @see <a href="https://cloud.google.com/endpoints/docs/grpc-service-config/reference/rpc/google.api#path-template-syntax">Path template syntax</a>
     */
    static List<PathSegment> parse(String path) {
        requireNonNull(path, "path");
        checkArgument(!path.isEmpty(), "path is empty.");

        final Context context = new Context(path);
        checkArgument(context.read() == '/', "path: %s (must start with '/')", context.path());

        final ImmutableList.Builder<PathSegment> segments = ImmutableList.builder();
        // Parse 'Segments' until the start symbol of 'Verb' part.
        // Basically, 'parse*' methods stop reading path characters if they see a delimiter. Also, they don't
        // consume the delimiter so that a caller can check the delimiter.
        segments.addAll(parseSegments(context, new Delimiters(':')));
        if (context.hasNext()) {
            // Consume the start symbol ':'.
            checkArgument(context.read() == ':',
                          "path: %s (invalid verb part at index %s)", context.path(), context.index());
            segments.add(new VerbPathSegment(context.readAll()));
        }

        final List<PathSegment> pathSegments = segments.build();
        checkArgument(!pathSegments.isEmpty(), "path: %s (must contain at least one segment)", context.path());
        return pathSegments;
    }

    /**
     * Parses 'Segments' part.
     *
     * <p>
     * Segments = Segment { "/" Segment }
     * </p>
     */
    private static List<PathSegment> parseSegments(Context context, Delimiters delimiters) {
        final Delimiters segmentDelimiters = delimiters.withMoreCharacter('/');
        final ImmutableList.Builder<PathSegment> segments = ImmutableList.builder();
        while (context.hasNext()) {
            final PathSegment parsedSegment = parseSegment(context, segmentDelimiters);
            segments.add(parsedSegment);

            if (!context.hasNext()) {
                return segments.build();
            }

            // Stop parsing if we meet one of the delimiters provided by the caller.
            final char c = context.peek();
            if (delimiters.contains(c)) {
                return segments.build();
            }

            // Check whether the last parsed segment has '**' literal. If so, there must be
            // no more segments.
            checkArgument(!containsDeepWildcardLiteral(parsedSegment),
                          "path: %s (must be no more segments after '**' literal at index %s)",
                          context.path(), context.index());

            // Consume the start symbol '/' of the next segment.
            checkArgument(context.read() == '/',
                          "path: %s (invalid segments part at index %s)", context.path(), context.index());
        }
        return segments.build();
    }

    private static boolean containsDeepWildcardLiteral(PathSegment segment) {
        if (segment instanceof DeepWildcardPathSegment) {
            return true;
        }
        if (segment instanceof VariablePathSegment) {
            return ((VariablePathSegment) segment)
                    .valueSegments().stream()
                    .anyMatch(HttpJsonTranscodingPathParser::containsDeepWildcardLiteral);
        }
        return false;
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
                // Consume the end symbol of a variable definition.
                checkArgument(context.read() == '}',
                              "path: %s (invalid variable part at index %s)", context.path(), context.index());
                return segment;
            }
            case '*': {
                final PathSegment segment;
                if (context.peek() == '*') {
                    // Consume the second '*'.
                    context.read();
                    segment = new DeepWildcardPathSegment(context.nextPathVarIndex());
                } else {
                    segment = new WildcardPathSegment(context.nextPathVarIndex(), null);
                }
                // Should 1) no more input or 2) meet a delimiter such as '/', ':' or '}'.
                checkArgument(!context.hasNext() || delimiters.contains(context.peek()),
                              "path: %s (invalid wildcard part at index %s)", context.path(), context.index());
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
        // Collect characters for 'FieldPath'.
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
        checkArgument(!fieldPath.isEmpty(),
                      "path: %s (invalid variable part at index %s)", context.path(), context.index());

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

    private HttpJsonTranscodingPathParser() {}

    static final class Context {
        private final String path;
        private int index;
        private int pathVarIndex;

        Context(String path) {
            this.path = path;
        }

        String path() {
            return path;
        }

        int index() {
            return index;
        }

        int nextPathVarIndex() {
            return pathVarIndex++;
        }

        boolean hasNext() {
            return index < path.length();
        }

        char read() {
            checkArgument(hasNext(), "path: %s (out of range at index %s)", path(), index());
            return path.charAt(index++);
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
            PARAMETERIZED, GLOB, REGEX,
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
        private static final Pattern VERB_PATTERN = Pattern.compile("([a-zA-Z0-9-_.~%]+)$");

        VerbPathSegment(String verb) {
            final Matcher matcher = VERB_PATTERN.matcher(verb);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("The provided verb '" + verb + "' is invalid. " +
                                                   "It must match the pattern: [a-zA-Z0-9-_.~%]+");
            }
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
            switch (type) {
                case PARAMETERIZED:
                    return ':' + pathVariable(type);
                case GLOB:
                    return "*";
                case REGEX:
                    return "(?<" + pathVariable(type) + ">[^/]+)";
            }
            throw new Error();
        }

        @Override
        public String pathVariable(PathMappingType type) {
            switch (type) {
                case PARAMETERIZED:
                    if (parentFieldPath != null) {
                        return parentFieldPath;
                    } else {
                        return 'p' + StringUtil.toString(pathVarIndex);
                    }
                case REGEX:
                    // A group name for regex must start with a letter and contain only letters, digits.
                    // parentFieldPath may contain non-alphanumeric characters, so we prepend 'p' to the index.
                    return 'p' + StringUtil.toString(pathVarIndex);
                case GLOB:
                    return StringUtil.toString(pathVarIndex);
            }
            // Should not reach here.
            throw new Error();
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
            checkArgument(type == PathMappingType.GLOB || type == PathMappingType.REGEX,
                          "'**' path segment is not supported by '%s' type", type);
            if (type == PathMappingType.GLOB) {
                return "**";
            } else {
                return "(?<" + pathVariable(PathMappingType.REGEX) + ">.+)";
            }
        }

        @Override
        public String pathVariable(PathMappingType type) {
            checkArgument(type == PathMappingType.GLOB || type == PathMappingType.REGEX,
                          "'**' path segment is not supported by '%s' type", type);
            if (type == PathMappingType.GLOB) {
                return StringUtil.toString(pathVarIndex);
            } else {
                return 'p' + StringUtil.toString(pathVarIndex);
            }
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

        String fieldPath() {
            return fieldPath;
        }

        List<PathSegment> valueSegments() {
            return valueSegments;
        }

        @Override
        public String segmentString(PathMappingType type) {
            return Stringifier.segmentsToPath(type, valueSegments, false);
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

        static String segmentsToPath(PathMappingType mappingType, List<PathSegment> segments,
                                     boolean withLeadingSlash) {
            requireNonNull(segments, "segments");
            final String path = toPathString(segments, mappingType);
            return withLeadingSlash ? '/' + path : path;
        }

        private static String toPathString(List<PathSegment> segments, PathMappingType type) {
            final PathSegment lastSegment = segments.get(segments.size() - 1);
            if (lastSegment instanceof VerbPathSegment) {
                final String basePath = concatWithSlash(segments.subList(0, segments.size() - 1), type);
                return basePath + ':' + lastSegment.segmentString(type);
            } else {
                return concatWithSlash(segments, type);
            }
        }

        private static String concatWithSlash(List<PathSegment> segments, PathMappingType type) {
            return segments.stream()
                           .map(segment -> segment.segmentString(type))
                           .collect(Collectors.joining("/"));
        }

        private Stringifier() {}
    }
}
