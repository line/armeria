/*
 * Copyright 2016 LINE Corporation
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
package com.linecorp.armeria.common.zookeeper;

import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import com.linecorp.armeria.client.Endpoint;

/**
 * Decode and encode between list of zNode value strings and list of {@link Endpoint}s.
 */
public interface NodeValueCodec {
    /**
     * Decode a zNode value into a list of {@link Endpoint}s.
     *
     * @param zNodeValue zNode value
     * @return the list of {@link Endpoint}s
     */
    Set<Endpoint> decodeAll(byte[] zNodeValue);

    /**
     * Decode a zNode value into a set of {@link Endpoint}s.
     *
     * @param zNodeValue zNode value
     * @return the list of {@link Endpoint}s
     */
    default Set<Endpoint> decodeAll(String zNodeValue) {
        requireNonNull(zNodeValue, "zNodeValue");
        return decodeAll(zNodeValue.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decode a zNode value to a {@link Endpoint}.
     * @param zNodeValue ZooKeeper node value
     * @return an {@link Endpoint}
     */
    Endpoint decode(byte[] zNodeValue);

    /**
     * Encode a set of {@link Endpoint}s into a bytes array representation
     * @param endpoints set of {@link Endpoint}s
     * @return a bytes array
     */
    byte[] encodeAll(Iterable<Endpoint> endpoints);

    /**
     * Encode single {@link Endpoint} into a bytes array representation
     * @param endpoint  an {@link Endpoint}
     * @return a bytes array
     */
    byte[] encode(Endpoint endpoint);
}
