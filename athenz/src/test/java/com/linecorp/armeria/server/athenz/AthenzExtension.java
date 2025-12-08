/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.server.athenz;

import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.File;
import java.net.URI;
import java.util.function.Consumer;

import org.junit.jupiter.api.extension.ExtensionContext;

import com.yahoo.athenz.zms.ZMSClient;

import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.client.athenz.ZtsBaseClientBuilder;
import com.linecorp.armeria.testing.junit5.common.AbstractAllOrEachExtension;

public class AthenzExtension extends AbstractAllOrEachExtension {

    private final AthenzDocker delegate;

    public AthenzExtension() {
        this(new File("src/test/resources/docker/docker-compose.yml"));
    }

    public AthenzExtension(File dockerComposeFile) {
        delegate = new AthenzDocker(dockerComposeFile) {
            @Override
            protected void scaffold(ZMSClient zmsClient) {
                AthenzExtension.this.scaffold(zmsClient);
            }
        };
    }

    public URI ztsUri() {
        return delegate.ztsUri();
    }

    public ZtsBaseClient newZtsBaseClient(String serviceName) {
        return delegate.newZtsBaseClient(serviceName);
    }

    public ZtsBaseClient newZtsBaseClient(String serviceName,
                                          Consumer<ZtsBaseClientBuilder> clientConfigurer) {
        return delegate.newZtsBaseClient(serviceName, clientConfigurer);
    }

    @Override
    protected void before(ExtensionContext context) throws Exception {
        assumeThat(delegate.initialize()).isTrue();
    }

    @Override
    public void after(ExtensionContext context) throws Exception {
        delegate.close();
    }

    /**
     * Override this method to create your own test domain, services, roles, and policies.
     */
    protected void scaffold(ZMSClient zmsClient) {}
}
