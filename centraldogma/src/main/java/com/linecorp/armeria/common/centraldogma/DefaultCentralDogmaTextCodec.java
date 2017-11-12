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

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.linecorp.armeria.client.Endpoint;

class DefaultCentralDogmaTextCodec implements CentralDogmaCodec<String> {

    @Override
    public List<Endpoint> decode(String content) {
        String[] lines = content.split(CodecUtil.segmentDelimiter);
        return Arrays.stream(lines)
                     .map(String::trim)
                     .map(CodecUtil::endpointFromString)
                     .collect(Collectors.toList());
    }

    @Override
    public String encode(List<Endpoint> endpoints) {
        return String.join(CodecUtil.segmentDelimiter,
                endpoints.stream().map(CodecUtil::endpointToString)
                 .collect(Collectors.toList()));
    }
}
