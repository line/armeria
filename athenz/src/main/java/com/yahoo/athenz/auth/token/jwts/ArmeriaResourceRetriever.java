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

package com.yahoo.athenz.auth.token.jwts;

import java.io.IOException;
import java.net.URL;

import com.nimbusds.jose.util.Resource;
import com.nimbusds.jose.util.ResourceRetriever;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A Nimbus {@link ResourceRetriever} implementation that uses an Armeria {@link WebClient}
 * to fetch JWKS resources. The {@code url} parameter passed to {@link #retrieveResource(URL)}
 * is ignored because the {@link WebClient} is already configured to target the correct ZTS host.
 */
final class ArmeriaResourceRetriever implements ResourceRetriever {

    private final WebClient webClient;
    private final String path;

    ArmeriaResourceRetriever(WebClient webClient, String path) {
        this.webClient = webClient;
        this.path = path;
    }

    @Override
    public Resource retrieveResource(URL url) throws IOException {
        final AggregatedHttpResponse res = webClient.blocking().get(path);
        if (!res.status().equals(HttpStatus.OK)) {
            throw new IOException("Failed to retrieve JWKS resource: " + res.status());
        }
        @Nullable
        final MediaType contentType = res.headers().contentType();
        final String contentTypeStr = contentType != null ? contentType.toString() : "application/json";
        return new Resource(res.contentUtf8(), contentTypeStr);
    }
}
