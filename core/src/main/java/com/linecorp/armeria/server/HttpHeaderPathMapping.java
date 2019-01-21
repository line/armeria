/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;

/**
 * A {@link PathMapping} based on {@link HttpMethod} and {@link MediaType}.
 */
final class HttpHeaderPathMapping implements PathMapping {

    private static final List<MediaType> ANY_TYPE = ImmutableList.of(MediaType.ANY_TYPE);
    private static final Joiner loggerNameJoiner = Joiner.on('_');
    private static final Joiner meterTagJoiner = Joiner.on(',');

    private final PathMapping pathStringMapping;
    private final Set<HttpMethod> supportedMethods;
    private final List<MediaType> consumeTypes;
    private final List<MediaType> produceTypes;

    private final String loggerName;
    private final String meterTag;

    private final int complexity;

    /**
     * Creates a new instance.
     */
    HttpHeaderPathMapping(PathMapping pathStringMapping, Set<HttpMethod> supportedMethods,
                          List<MediaType> consumeTypes, List<MediaType> produceTypes) {
        this.pathStringMapping = requireNonNull(pathStringMapping, "pathStringMapping");
        this.supportedMethods = requireNonNull(supportedMethods, "supportedMethods");
        this.consumeTypes = requireNonNull(consumeTypes, "consumeTypes");
        this.produceTypes = requireNonNull(produceTypes, "produceTypes");

        loggerName = generateLoggerName(pathStringMapping.loggerName(),
                                        supportedMethods, consumeTypes, produceTypes);
        meterTag = generateMeterTag(pathStringMapping.meterTag(),
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
            if (mappingCtx.isCorsPreflight() && !mappingCtx.delayedThrowable().isPresent()) {
                // '403 Forbidden' is better for a CORS preflight request than '404 Not Found'.
                mappingCtx.delayThrowable(HttpStatusException.of(HttpStatus.FORBIDDEN));
            }
            return PathMappingResult.empty();
        }

        // We need to check the method after checking the path, in order to return '405 Method Not Allowed'.
        // If the request is a CORS preflight, we don't care whether the path mapping supports OPTIONS method.
        // The request may be always passed into the designated service, but most of cases, it will be handled
        // by a CorsService decorator before it reaches the final service.
        if (!mappingCtx.isCorsPreflight() && !supportedMethods.contains(mappingCtx.method())) {
            // '415 Unsupported Media Type' and '406 Not Acceptable' is more specific than
            // '405 Method Not Allowed'. So 405 would be set if there is no status code set before.
            if (!mappingCtx.delayedThrowable().isPresent()) {
                mappingCtx.delayThrowable(HttpStatusException.of(HttpStatus.METHOD_NOT_ALLOWED));
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
                mappingCtx.delayThrowable(HttpStatusException.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
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
                        result.setNegotiatedResponseMediaType(produceType);
                    }
                    return result;
                }
            }
        }

        mappingCtx.delayThrowable(HttpStatusException.of(HttpStatus.NOT_ACCEPTABLE));
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
    public String meterTag() {
        return meterTag;
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
    public Optional<String> regex() {
        return pathStringMapping.regex();
    }

    @Override
    public int complexity() {
        return complexity;
    }

    @Override
    public Set<HttpMethod> supportedMethods() {
        return supportedMethods;
    }

    @Override
    public List<MediaType> consumeTypes() {
        return consumeTypes;
    }

    @Override
    public List<MediaType> produceTypes() {
        return produceTypes;
    }

    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();
        buf.append('[');
        buf.append(pathStringMapping);
        buf.append(", ");
        buf.append(MoreObjects.toStringHelper("")
                              .add("supportedMethods", supportedMethods)
                              .add("consumeTypes", consumeTypes)
                              .add("produceTypes", produceTypes));
        buf.append(']');
        return buf.toString();
    }

    @Override
    public PathMapping withHttpHeaderInfo(Set<HttpMethod> supportedMethods, List<MediaType> consumeTypes,
                                          List<MediaType> produceTypes) {
        return new HttpHeaderPathMapping(pathStringMapping, supportedMethods, consumeTypes, produceTypes);
    }

    private static String generateLoggerName(String prefix, Set<HttpMethod> supportedMethods,
                                             List<MediaType> consumeTypes, List<MediaType> produceTypes) {
        final StringJoiner name = new StringJoiner(".");
        name.add(prefix);
        name.add(loggerNameJoiner.join(supportedMethods.stream().sorted().iterator()));

        // The following three cases should be different to each other.
        // Each name would be produced as follows:
        //
        // consumeTypes: text/plain, text/html               -> consumes.text_plain.text_html
        // consumeTypes: text/plain, produceTypes: text/html -> consumes.text_plain.produces.text_html
        // produceTypes: text/plain, text/html               -> produces.text_plain.text_html

        if (!consumeTypes.isEmpty()) {
            name.add("consumes");
            consumeTypes.forEach(e -> name.add(e.type() + '_' + e.subtype()));
        }
        if (!produceTypes.isEmpty()) {
            name.add("produces");
            produceTypes.forEach(e -> name.add(e.type() + '_' + e.subtype()));
        }
        return name.toString();
    }

    private static String generateMeterTag(String parentTag, Set<HttpMethod> supportedMethods,
                                           List<MediaType> consumeTypes, List<MediaType> produceTypes) {

        final StringJoiner name = new StringJoiner(",");
        name.add(parentTag);
        name.add("methods:" + meterTagJoiner.join(supportedMethods.stream().sorted().iterator()));

        // The following three cases should be different to each other.
        // Each name would be produced as follows:
        //
        // consumeTypes: text/plain, text/html               -> "consumes:text/plain,text/html"
        // consumeTypes: text/plain, produceTypes: text/html -> "consumes:text/plain,produces:text/html"
        // produceTypes: text/plain, text/html               -> "produces:text/plain,text/html"

        addMediaTypes(name, "consumes", consumeTypes);
        addMediaTypes(name, "produces", produceTypes);

        return name.toString();
    }

    private static void addMediaTypes(StringJoiner builder, String prefix, List<MediaType> mediaTypes) {
        if (!mediaTypes.isEmpty()) {
            final StringBuilder buf = new StringBuilder();
            buf.append(prefix).append(':');
            for (MediaType t : mediaTypes) {
                buf.append(t.type());
                buf.append('/');
                buf.append(t.subtype());
                buf.append(',');
            }
            buf.setLength(buf.length() - 1);
            builder.add(buf.toString());
        }
    }
}
