/*
 * Copyright 2015 LINE Corporation
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
package com.linecorp.armeria.server.tomcat;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;

import org.apache.catalina.LifecycleState;
import org.apache.catalina.Realm;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardEngine;
import org.apache.catalina.core.StandardServer;
import org.apache.catalina.core.StandardService;
import org.apache.catalina.realm.NullRealm;
import org.apache.catalina.startup.Tomcat;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Builds a {@link TomcatService}. Use the factory methods in {@link TomcatService} if you do not override
 * the default settings or you have a configured {@link Tomcat} or {@link Connector} instance.
 */
public final class TomcatServiceBuilder {

    // From Tomcat conf/server.xml
    private static final String DEFAULT_SERVICE_NAME = "Catalina";

    private final Path docBase;
    @Nullable
    private final String jarRoot;
    private final List<Consumer<? super StandardServer>> configurators = new ArrayList<>();

    private String serviceName = DEFAULT_SERVICE_NAME;
    @Nullable
    private String engineName;
    @Nullable
    private Path baseDir;
    @Nullable
    private Realm realm;
    @Nullable
    private String hostname;

    TomcatServiceBuilder(Path docBase, @Nullable String jarRoot) {
        this.docBase = docBase;
        this.jarRoot = jarRoot;
    }

    /**
     * Sets the name of the {@link StandardService} of an embedded Tomcat. The default serviceName is
     * {@code "Catalina"}.
     */
    public TomcatServiceBuilder serviceName(String serviceName) {
        this.serviceName = requireNonNull(serviceName, "serviceName");
        return this;
    }

    /**
     * Sets the name of the {@link StandardEngine} of an embedded Tomcat. {@link #serviceName(String)} will be
     * used instead if not set.
     */
    public TomcatServiceBuilder engineName(String engineName) {
        this.engineName = requireNonNull(engineName, "engineName");
        return this;
    }

    /**
     * Sets the base directory of an embedded Tomcat.
     */
    public TomcatServiceBuilder baseDir(String baseDir) {
        return baseDir(Paths.get(requireNonNull(baseDir, "baseDir")));
    }

    /**
     * Sets the base directory of an embedded Tomcat.
     */
    public TomcatServiceBuilder baseDir(Path baseDir) {
        baseDir = requireNonNull(baseDir, "baseDir").toAbsolutePath();
        if (!Files.isDirectory(baseDir)) {
            throw new IllegalArgumentException("baseDir: " + baseDir + " (expected: a directory)");
        }

        this.baseDir = baseDir;
        return this;
    }

    /**
     * Sets the {@link Realm} of an embedded Tomcat.
     */
    public TomcatServiceBuilder realm(Realm realm) {
        requireNonNull(realm, "realm");
        this.realm = realm;
        return this;
    }

    /**
     * Sets the hostname of an embedded Tomcat.
     */
    public TomcatServiceBuilder hostname(String hostname) {
        this.hostname = validateHostname(hostname);
        return this;
    }

    private static String validateHostname(String hostname) {
        requireNonNull(hostname, "hostname");
        if (hostname.isEmpty()) {
            throw new IllegalArgumentException("hostname is empty.");
        }
        return hostname;
    }

    /**
     * Adds a {@link Consumer} that performs additional configuration operations against
     * the Tomcat {@link StandardServer} created by a {@link TomcatService}.
     */
    public TomcatServiceBuilder configurator(Consumer<? super StandardServer> configurator) {
        configurators.add(requireNonNull(configurator, "configurator"));
        return this;
    }

    /**
     * Returns a newly-created {@link TomcatService} based on the properties of this builder.
     */
    public TomcatService build() {
        // Create a temporary directory and use it if baseDir is not set.
        @Nullable Path baseDir = this.baseDir;
        if (baseDir == null) {
            try {
                baseDir = Files.createTempDirectory("tomcat-armeria.");
            } catch (IOException e) {
                throw new TomcatServiceException("failed to create a temporary directory", e);
            }

            try {
                Files.setPosixFilePermissions(baseDir, EnumSet.of(PosixFilePermission.OWNER_READ,
                                                                  PosixFilePermission.OWNER_WRITE,
                                                                  PosixFilePermission.OWNER_EXECUTE));
            } catch (UnsupportedOperationException ignored) {
                // Windows?
            } catch (IOException e) {
                throw new TomcatServiceException("failed to secure a temporary directory", e);
            }
        }

        // Use a NullRealm if no Realm was specified.
        @Nullable Realm realm = this.realm;
        if (realm == null) {
            realm = new NullRealm();
        }

        final Consumer<Connector> postStopTask = connector -> {
            @SuppressWarnings("UnnecessaryFullyQualifiedName")
            final org.apache.catalina.Server server = connector.getService().getServer();
            if (server.getState() == LifecycleState.STOPPED) {
                try {
                    TomcatService.logger.info("Destroying an embedded Tomcat: {}",
                                              TomcatService.toString(server));
                    server.destroy();
                } catch (Exception e) {
                    TomcatService.logger.warn("Failed to destroy an embedded Tomcat: {}",
                                              TomcatService.toString(server), e);
                }
            }
        };

        return new ManagedTomcatService(null, new ManagedConnectorFactory(new TomcatServiceConfig(
                serviceName, engineName, baseDir, realm, hostname, docBase, jarRoot,
                Collections.unmodifiableList(configurators))), postStopTask);
    }

    @Override
    public String toString() {
        return TomcatServiceConfig.toString(
                this, serviceName, engineName, baseDir, realm, hostname, docBase, jarRoot);
    }
}
