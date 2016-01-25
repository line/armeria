/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.server.http.tomcat;

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import org.apache.catalina.Realm;
import org.apache.catalina.core.StandardEngine;
import org.apache.catalina.core.StandardServer;
import org.apache.catalina.core.StandardService;
import org.apache.catalina.realm.NullRealm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a {@link TomcatService} and its {@link TomcatServiceConfig}. Use the factory methods in
 * {@link TomcatService} if you do not override the default settings.
 */
public final class TomcatServiceBuilder {

    private static final Logger logger = LoggerFactory.getLogger(TomcatServiceBuilder.class);

    // From Tomcat conf/server.xml
    private static final String DEFAULT_SERVICE_NAME = "Catalina";
    private static final String DEFAULT_ENGINE_NAME = DEFAULT_SERVICE_NAME;

    /**
     * Creates a new {@link TomcatServiceBuilder} with the web application at the root directory inside the
     * JAR/WAR/directory where the caller class is located at.
     */
    public static TomcatServiceBuilder forCurrentClassPath() {
        return forCurrentClassPath(2);
    }

    /**
     * Creates a new {@link TomcatServiceBuilder} with the web application at the specified document base
     * directory inside the JAR/WAR/directory where the caller class is located at.
     */
    public static TomcatServiceBuilder forCurrentClassPath(String docBase) {
        return forCurrentClassPath(docBase, 2);
    }

    static TomcatServiceBuilder forCurrentClassPath(int callDepth) {
        return forCurrentClassPath("", callDepth);
    }

    static TomcatServiceBuilder forCurrentClassPath(String docBase, int callDepth) {
        final Class<?> callerClass = TomcatUtil.classContext()[callDepth];
        logger.debug("Creating a Tomcat service with the caller class: {}", callerClass.getName());
        return forClassPath(callerClass, docBase);
    }

    /**
     * Creates a new {@link TomcatServiceBuilder} with the web application at the root directory inside the
     * JAR/WAR/directory where the specified class is located at.
     */
    public static TomcatServiceBuilder forClassPath(Class<?> clazz) {
        return forClassPath(clazz, "");
    }

    /**
     * Creates a new {@link TomcatServiceBuilder} with the web application at the specified document base
     * directory inside the JAR/WAR/directory where the specified class is located at.
     */
    public static TomcatServiceBuilder forClassPath(Class<?> clazz, String docBase) {
        requireNonNull(clazz, "clazz");
        requireNonNull(docBase, "docBase");

        final ProtectionDomain pd = clazz.getProtectionDomain();
        if (pd == null) {
            throw new IllegalArgumentException(clazz + " does not have a protection domain.");
        }
        final CodeSource cs = pd.getCodeSource();
        if (cs == null) {
            throw new IllegalArgumentException(clazz + " does not have a code source.");
        }
        final URL url = cs.getLocation();
        if (url == null) {
            throw new IllegalArgumentException(clazz + " does not have a location.");
        }

        if (!"file".equalsIgnoreCase(url.getProtocol())) {
            throw new IllegalArgumentException(clazz + " is not on a file system: " + url);
        }

        File f;
        try {
            f = new File(url.toURI());
        } catch (URISyntaxException ignored) {
            f = new File(url.getPath());
        }

        f = fileSystemDocBase(f, docBase);

        if (!f.isDirectory()) {
            throw new IllegalArgumentException(f + " is not a directory.");
        }

        return forFileSystem(f.toPath());
    }

    private static File fileSystemDocBase(File rootDir, String classPathDocBase) {
        // Append the specified docBase to the root directory to build the actual docBase on file system.
        String fileSystemDocBase = rootDir.getPath();
        classPathDocBase = classPathDocBase.replace('/', File.separatorChar);
        if (fileSystemDocBase.endsWith(File.separator)) {
            if (classPathDocBase.startsWith(File.separator)) {
                fileSystemDocBase += classPathDocBase.substring(1);
            } else {
                fileSystemDocBase += classPathDocBase;
            }
        } else {
            if (classPathDocBase.startsWith(File.separator)) {
                fileSystemDocBase += classPathDocBase;
            } else {
                fileSystemDocBase = fileSystemDocBase + File.separatorChar + classPathDocBase;
            }
        }

        return new File(fileSystemDocBase);
    }

    /**
     * Creates a new {@link TomcatServiceBuilder} with the web application at the specified document base,
     * which can be a directory or a JAR/WAR file.
     */
    public static TomcatServiceBuilder forFileSystem(String docBase) {
        return forFileSystem(Paths.get(requireNonNull(docBase, "docBase")));
    }

    /**
     * Creates a new {@link TomcatServiceBuilder} with the web application at the specified document base,
     * which can be a directory or a JAR/WAR file.
     */
    public static TomcatServiceBuilder forFileSystem(Path docBase) {
        return new TomcatServiceBuilder(docBase);
    }

    private static final Realm NULL_REALM = new NullRealm();

    private final Path docBase;
    private final List<Consumer<? super StandardServer>> configurators = new ArrayList<>();

    private String serviceName = DEFAULT_SERVICE_NAME;
    private String engineName = DEFAULT_ENGINE_NAME;
    private Path baseDir;
    private Realm realm = NULL_REALM;
    private String hostname = "localhost";

    private TomcatServiceBuilder(Path docBase) {
        this.docBase = validateDocBase(docBase);
    }

    private static Path validateDocBase(Path docBase) {
        requireNonNull(docBase, "docBase");

        docBase = docBase.toAbsolutePath();

        if (!Files.exists(docBase)) {
            throw new IllegalArgumentException("docBase: " + docBase + " (non-existent)");
        }

        if (Files.isDirectory(docBase)) {
            return docBase;
        }

        final String docBaseLowerCased = docBase.toString().toLowerCase(Locale.US);
        if (!Files.isRegularFile(docBase) ||
            !docBaseLowerCased.endsWith(".jar") &&
            !docBaseLowerCased.endsWith(".war")) {
            throw new IllegalArgumentException(
                    "docBase: " + docBase + " (expected: a directory or a WAR/JAR file)");
        }

        return docBase;
    }

    /**
     * Sets the name of the {@link StandardService} of an embedded Tomcat.
     */
    public TomcatServiceBuilder serviceName(String serviceName) {
        this.serviceName = requireNonNull(serviceName, "serviceName");
        return this;
    }

    /**
     * Sets the name of the {@link StandardEngine} of an embedded Tomcat.
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
     * Sets a {@link Consumer} that performs additional configuration operations against
     * the Tomcat {@link StandardServer} created by a {@link TomcatService}. This method can be invoked
     * multiple times to add multiple {@code configurator}s.
     */
    public TomcatServiceBuilder configurator(Consumer<? super StandardServer> configurator) {
        configurators.add(requireNonNull(configurator, "configurator"));
        return this;
    }

    /**
     * Creates a new {@link TomcatService}.
     */
    public TomcatService build() {
        // Create a temporary directory and use it if baseDir is not set.
        Path baseDir = this.baseDir;
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

        return new TomcatService(new TomcatServiceConfig(
                serviceName, engineName, baseDir, realm, hostname, docBase,
                Collections.unmodifiableList(configurators)));
    }

    @Override
    public String toString() {
        return TomcatServiceConfig.toString(this, serviceName, engineName, baseDir, realm, hostname, docBase);
    }
}
