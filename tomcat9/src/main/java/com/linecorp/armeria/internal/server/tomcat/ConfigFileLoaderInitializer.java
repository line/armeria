/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.internal.server.tomcat;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;

import org.apache.catalina.startup.ContextConfig;
import org.apache.tomcat.util.file.ConfigFileLoader;
import org.apache.tomcat.util.file.ConfigurationSource;

/**
 * Sets the dummy {@link ConfigurationSource} so that {@link ContextConfig} does not complain.
 */
public final class ConfigFileLoaderInitializer {

    public static void init() {
        try {
            ConfigFileLoader.getSource(); // throws IllegalStateException if source is not set.
        } catch (Exception e) {
            // Set only when a user did not set the source.
            ConfigFileLoader.setSource(new ConfigurationSource() {
                @Override
                public Resource getResource(String name) throws IOException {
                    throw new FileNotFoundException(name);
                }

                @Override
                public URI getURI(String name) {
                    throw new UnsupportedOperationException();
                }
            });
        }
    }

    private ConfigFileLoaderInitializer() {}
}
