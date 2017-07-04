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

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;

/**
 * A {@link PathMapping} based on {@link HttpMethod} and {@link MediaType}.
 */
final class HttpHeaderPathMapping implements PathMapping {

    private final PathMapping pathStringMapping;
    private final Set<HttpMethod> supportedMethods;
    @Nullable
    private final MediaType consumeType;
    @Nullable
    private final MediaType produceType;

    private final String loggerName;
    private final String metricName;

    HttpHeaderPathMapping(PathMapping pathStringMapping, Set<HttpMethod> supportedMethods,
                          @Nullable MediaType consumeType, @Nullable MediaType produceType) {
        this.pathStringMapping = requireNonNull(pathStringMapping, "pathStringMapping");
        this.supportedMethods = requireNonNull(supportedMethods, "supportedMethods");
        this.consumeType = consumeType;
        this.produceType = produceType;

        loggerName = generateName(".", pathStringMapping.loggerName(),
                                  supportedMethods, consumeType, produceType);
        metricName = generateName("/", pathStringMapping.metricName(),
                                  supportedMethods, consumeType, produceType);
    }

    @Override
    public PathMappingResult apply(PathMappingContext mappingCtx) {
        if (!supportedMethods.contains(mappingCtx.method())) {
            return PathMappingResult.empty();
        }

        final PathMappingResult result = pathStringMapping.apply(mappingCtx);
        if (result.isPresent()) {
            if (consumeType != null) {
                final MediaType type = mappingCtx.consumeType();
                if (type == null || !type.belongsTo(consumeType)) {
                    return PathMappingResult.empty();
                }
            }
            if (produceType != null) {
                final List<MediaType> types = mappingCtx.produceTypes();
                for (int i = 0; i < types.size(); i++) {
                    if (produceType.belongsTo(types.get(i))) {
                        // To early stop path mapping traversal,
                        // we set the score as the best score when the index is 0.
                        result.setScore(i == 0 ? PathMappingResult.HIGHEST_SCORE : -1 * i);
                        return result;
                    }
                }
                return PathMappingResult.empty();
            }
        }
        return result;
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

    public PathMapping pathStringMapping() {
        return pathStringMapping;
    }

    public Set<HttpMethod> supportedMethods() {
        return supportedMethods;
    }

    @Nullable
    public MediaType consumeType() {
        return consumeType;
    }

    @Nullable
    public MediaType produceType() {
        return produceType;
    }

    private static String generateName(String delim, String prefix, Set<HttpMethod> supportedMethods,
                                       MediaType consumeType, MediaType produceType) {
        final StringJoiner methodNames = new StringJoiner("_");
        supportedMethods.forEach(e -> methodNames.add(e.name()));
        StringJoiner name = new StringJoiner(delim).add(prefix)
                                                   .add(methodNames.toString());
        if (consumeType != null) {
            name.add(consumeType.type() + '_' + consumeType.subtype());
        } else {
            name.add("_");
        }
        if (produceType != null) {
            name.add(produceType.type() + '_' + produceType.subtype());
        } else {
            name.add("_");
        }
        return name.toString();
    }
}
