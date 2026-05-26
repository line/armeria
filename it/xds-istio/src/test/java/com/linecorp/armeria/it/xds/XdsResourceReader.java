/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.it.xds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.protobuf.GeneratedMessageV3;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;

final class XdsResourceReader {

    private static final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    static <T extends GeneratedMessageV3> T fromYaml(String yaml, Class<T> clazz) {
        return com.linecorp.armeria.xds.XdsResourceReader.from(yaml, clazz);
    }

    static <T extends GeneratedMessageV3> T fromJson(String json, Class<T> clazz) {
        return com.linecorp.armeria.xds.XdsResourceReader.from(json, clazz);
    }

    /**
     * Rewrites the {@code xds-grpc} cluster's load assignment to connect directly to
     * Istiod's plaintext gRPC port (15010) instead of pilot-agent's UDS proxy.
     * This is due to the restriction that pilot-agent only allows a single active connection.
     */
    static String rewriteXdsGrpcBootstrap(String bootstrapJson) {
        return JsonPath.parse(bootstrapJson)
                       .set("$.static_resources.clusters[?(@.name=='xds-grpc')].load_assignment",
                            Configuration.defaultConfiguration().jsonProvider().parse("""
                                {
                                  "cluster_name": "xds-grpc",
                                  "endpoints": [{
                                    "lb_endpoints": [{
                                      "endpoint": {
                                        "address": {
                                          "socket_address": {
                                            "address": "istiod.istio-system.svc",
                                            "port_value": 15010
                                          }
                                        }
                                      }
                                    }]
                                  }]
                                }
                                """))
                       .jsonString();
    }

    /**
     * Adds a listener (specified as YAML) to the bootstrap JSON's
     * {@code static_resources.listeners} array.
     */
    static String addStaticListener(String bootstrapJson, String listenerYaml) {
        try {
            final JsonNode listenerNode = mapper.reader().readTree(listenerYaml);
            final Object listenerObj = Configuration.defaultConfiguration()
                                                    .jsonProvider()
                                                    .parse(listenerNode.toString());
            return JsonPath.parse(bootstrapJson)
                           .add("$.static_resources.listeners", listenerObj)
                           .jsonString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private XdsResourceReader() {}
}
