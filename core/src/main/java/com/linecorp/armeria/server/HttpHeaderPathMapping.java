/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;

/**
 * A {@link PathMapping} based on {@link HttpMethod} and {@link MediaType}.
 */
final class HttpHeaderPathMapping implements PathMapping {

    private static final HttpResponseException METHOD_NOT_ALLOWED_EXCEPTION =
            HttpResponseException.of(HttpStatus.METHOD_NOT_ALLOWED);
    private static final HttpResponseException NOT_ACCEPTABLE_EXCEPTION =
            HttpResponseException.of(HttpStatus.NOT_ACCEPTABLE);
    private static final HttpResponseException UNSUPPORTED_MEDIA_TYPE_EXCEPTION =
            HttpResponseException.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE);

    private static final List<MediaType> ANY_TYPE = ImmutableList.of(MediaType.ANY_TYPE);

    private final PathMapping pathStringMapping;
    private final Set<HttpMethod> supportedMethods;
    private final List<MediaType> consumeTypes;
    private final List<MediaType> produceTypes;

    private final String loggerName;
    private final String metricName;

    private final int complexity;

    HttpHeaderPathMapping(PathMapping pathStringMapping, Set<HttpMethod> supportedMethods,
                          List<MediaType> consumeTypes, List<MediaType> produceTypes) {
        this.pathStringMapping = requireNonNull(pathStringMapping, "pathStringMapping");
        this.supportedMethods = requireNonNull(supportedMethods, "supportedMethods");
        this.consumeTypes = requireNonNull(consumeTypes, "consumeTypes");
        this.produceTypes = requireNonNull(produceTypes, "produceTypes");

        loggerName = generateName(".", pathStringMapping.loggerName(),
                                  supportedMethods, consumeTypes, produceTypes);
        metricName = generateName("/", pathStringMapping.metricName(),
                                  supportedMethods, consumeTypes, produceTypes);

        // Starts with 1 due to the HTTP method mapping.
        int complexity = 1;
        if (!consumeTypes.isEmpty()) {
            complexity += 2;
        }
        if (!produceTypes.isEmpty()) {
            complexity += 4;
        }
        this.complexity = complexity;
    }

    @Override
    public PathMappingResult apply(PathMappingContext mappingCtx) {
        final PathMappingResult result = pathStringMapping.apply(mappingCtx);
        if (!result.isPresent()) {
            return PathMappingResult.empty();
        }

        // We need to check the method at the last in order to return '405 Method Not Allowed'.
        if (!supportedMethods.contains(mappingCtx.method())) {
            // '415 Unsupported Media Type' and '406 Not Acceptable' is more specific than
            // '405 Method Not Allowed'. So 405 would be set if there is no status code set before.
            if (!mappingCtx.delayedThrowable().isPresent()) {
                mappingCtx.delayThrowable(METHOD_NOT_ALLOWED_EXCEPTION);
            }
            return PathMappingResult.empty();
        }

        if (!consumeTypes.isEmpty()) {
            final MediaType type = mappingCtx.consumeType();
            boolean found = false;
            if (type != null) {
                for (MediaType mediaType : consumeTypes) {
                    found = type.belongsTo(mediaType);
                    if (found) {
                        break;
                    }
                }
            }
            if (!found) {
                mappingCtx.delayThrowable(UNSUPPORTED_MEDIA_TYPE_EXCEPTION);
                return PathMappingResult.empty();
            }
        }

        // If a request does not have 'Accept' header, it would be handled the same as 'Accept: */*'.
        // So there is a '*/*' at least in the 'mappingCtx.produceTypes()' if the virtual host supports
        // the media type negotiation.
        final List<MediaType> types = mappingCtx.produceTypes();
        if (types == null) {
            // Media type negotiation is not supported on this virtual host.
            return result;
        }

        // Also, the missing '@ProduceTypes' would be handled the same as '@ProduceTypes({"*/*"})'.
        final List<MediaType> producibleTypes = produceTypes.isEmpty() ? ANY_TYPE : produceTypes;
        for (MediaType produceType : producibleTypes) {
            for (int i = 0; i < types.size(); i++) {
                if (produceType.belongsTo(types.get(i))) {
                    // To early stop path mapping traversal,
                    // we set the score as the best score when the index is 0.
                    result.setScore(i == 0 ? PathMappingResult.HIGHEST_SCORE : -1 * i);
                    if (!produceTypes.isEmpty()) {
                        result.setNegotiatedProduceType(produceType);
                    }
                    return result;
                }
            }
        }

        mappingCtx.delayThrowable(NOT_ACCEPTABLE_EXCEPTION);
        return PathMappingResult.empty();
    }

    @Override
    public Set<String> paramNames() {
        return pathStringMapping.paramNames();
    }

    @Override
    public String loggerName() {
        return loggerName;
    }

    @Override
    public String metricName() {
        return metricName;
    }

    @Override
    public Optional<String> exactPath() {
        return pathStringMapping.exactPath();
    }

    @Override
    public Optional<String> prefix() {
        return pathStringMapping.prefix();
    }

    @Override
    public Optional<String> triePath() {
        return pathStringMapping.triePath();
    }

    @Override
    public int complexity() {
        return complexity;
    }

    public PathMapping pathStringMapping() {
        return pathStringMapping;
    }

    public Set<HttpMethod> supportedMethods() {
        return supportedMethods;
    }

    public List<MediaType> consumeTypes() {
        return consumeTypes;
    }

    public List<MediaType> produceTypes() {
        return produceTypes;
    }

    @Override
    public String toString() {
        return metricName();
    }

    private static String generateName(String delim, String prefix, Set<HttpMethod> supportedMethods,
                                       List<MediaType> consumeTypes, List<MediaType> produceTypes) {
        final StringJoiner methodNames = new StringJoiner("_");
        supportedMethods.forEach(e -> methodNames.add(e.name()));
        StringJoiner name = new StringJoiner(delim).add(prefix)
                                                   .add(methodNames.toString());

        // The following three cases should be different to each other.
        // Each name would be produced as follows if the delimiter is assumed as '/'.
        //
        // consumeTypes: text/plain, text/html               -> /consumes/text_plain/text_html/produces/_
        // consumeTypes: text/plain, produceTypes: text/html -> /consumes/text_plain/produces/text_html
        // produceTypes: text/plain, text/html               -> /consumes/_/produces/text_plain/text_html

        name.add("consumes");
        if (consumeTypes.isEmpty()) {
            name.add("_");
        } else {
            consumeTypes.forEach(e -> name.add(e.type() + '_' + e.subtype()));
        }
        name.add("produces");
        if (produceTypes.isEmpty()) {
            name.add("_");
        } else {
            produceTypes.forEach(e -> name.add(e.type() + '_' + e.subtype()));
        }
        return name.toString();
    }
}
