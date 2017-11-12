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
package com.linecorp.armeria.common.centraldogma;

import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;

import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.Endpoint;

class DefaultCentralDogmaJsonCodec implements CentralDogmaCodec<JsonNode> {
    private static final ObjectMapper objectMapper =
            new ObjectMapper().disable(FAIL_ON_UNKNOWN_PROPERTIES);

    @Override
    public List<Endpoint> decode(JsonNode jsonNodes) {
        try {
            List<String> nodes = objectMapper.treeToValue(jsonNodes, List.class);
            return nodes.stream()
                    .map(String::trim)
                    .map(CodecUtil::endpointFromString).collect(Collectors.toList());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public JsonNode encode(List<Endpoint> endpoints) {
        List<String> endpointStr = endpoints.stream()
                                                .map(CodecUtil::endpointToString)
                                                .collect(Collectors.toList());
        return objectMapper.valueToTree(endpointStr);
    }
}
