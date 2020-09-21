/*
 * Copyright 2016 LINE Corporation
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

// This file is forked from Netty,
// https://github.com/netty/netty/tree/4.1/common/src/main/java/io/netty/util/Version.java

/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
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
package com.linecorp.armeria.common.util;

import static java.util.Objects.requireNonNull;

import java.io.InputStream;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.MapMaker;
import com.google.common.io.Closeables;

/**
 * Retrieves the version information of available Armeria artifacts.
 *
 * <p>This class retrieves the version information from
 * {@code META-INF/com.linecorp.armeria.versions.properties}, which is generated in build time. Note that
 * it may not be possible to retrieve the information completely, depending on your environment, such as
 * the specified {@link ClassLoader}, the current {@link SecurityManager}.
 */
public final class Version {

    // Forked from Netty 4.1.34 at d0912f27091e4548466df81f545c017a25c9d256

    private static final Logger logger = LoggerFactory.getLogger(Version.class);

    private static final String PROP_RESOURCE_PATH = "META-INF/com.linecorp.armeria.versions.properties";

    private static final String PROP_VERSION = ".version";
    private static final String PROP_COMMIT_DATE = ".commitDate";
    private static final String PROP_SHORT_COMMIT_HASH = ".shortCommitHash";
    private static final String PROP_LONG_COMMIT_HASH = ".longCommitHash";
    private static final String PROP_REPO_STATUS = ".repoStatus";

    private static final Map<ClassLoader, Map<String, Version>> VERSIONS =
            new MapMaker().weakKeys().makeMap();

    /**
     * Returns the version information for the Armeria artifact named {@code artifactId}. If information for
     * the artifact can't be found, a default value is returned with arbitrary {@code unknown} values.
     */
    public static Version get(String artifactId) {
        return get(artifactId, Version.class.getClassLoader());
    }

    /**
     * Returns the version information for the Armeria artifact named {@code artifactId} using the specified
     * {@link ClassLoader}. If information for the artifact can't be found, a default value is returned
     * with arbitrary {@code unknown} values.
     */
    public static Version get(String artifactId, ClassLoader classLoader) {
        requireNonNull(artifactId, "artifactId");
        final Version version = getAll(classLoader).get(artifactId);
        if (version != null) {
            return version;
        }
        return new Version(
                artifactId,
                "unknown",
                0,
                "unknown",
                "unknown",
                "unknown");
    }

    /**
     * Retrieves the version information of Armeria artifacts.
     * This method is a shortcut for {@link #getAll(ClassLoader) getAll(Version.class.getClassLoader())}.
     *
     * @return A {@link Map} whose keys are Maven artifact IDs and whose values are {@link Version}s
     */
    public static Map<String, Version> getAll() {
        return getAll(Version.class.getClassLoader());
    }

    /**
     * Retrieves the version information of Armeria artifacts using the specified {@link ClassLoader}.
     *
     * @return A {@link Map} whose keys are Maven artifact IDs and whose values are {@link Version}s
     */
    public static Map<String, Version> getAll(ClassLoader classLoader) {
        requireNonNull(classLoader, "classLoader");

        return VERSIONS.computeIfAbsent(classLoader, cl -> {
            boolean foundProperties = false;

            // Collect all properties.
            final Properties props = new Properties();
            try {
                final Enumeration<URL> resources = cl.getResources(PROP_RESOURCE_PATH);
                while (resources.hasMoreElements()) {
                    foundProperties = true;
                    final URL url = resources.nextElement();
                    final InputStream in = url.openStream();
                    try {
                        props.load(in);
                    } finally {
                        Closeables.closeQuietly(in);
                    }
                }
            } catch (Exception ignore) {
                // Not critical. Just ignore.
            }

            if (!foundProperties) {
                logger.info(
                        "Could not find any property files at " +
                        "META-INF/com.linecorp.armeria.versions.properties. " +
                        "This usually indicates an issue with your application packaging, for example using " +
                        "a fat JAR method that only keeps one copy of any file. For maximum functionality, " +
                        "it is recommended to fix your packaging to include these files.");
                return ImmutableMap.of();
            }

            // Collect all artifactIds.
            final Set<String> artifactIds = new HashSet<>();
            for (Object o : props.keySet()) {
                final String k = (String) o;

                final int dotIndex = k.indexOf('.');
                if (dotIndex <= 0) {
                    continue;
                }

                final String artifactId = k.substring(0, dotIndex);

                // Skip the entries without required information.
                if (!props.containsKey(artifactId + PROP_VERSION) ||
                    !props.containsKey(artifactId + PROP_COMMIT_DATE) ||
                    !props.containsKey(artifactId + PROP_SHORT_COMMIT_HASH) ||
                    !props.containsKey(artifactId + PROP_LONG_COMMIT_HASH) ||
                    !props.containsKey(artifactId + PROP_REPO_STATUS)) {
                    continue;
                }

                artifactIds.add(artifactId);
            }

            final ImmutableSortedMap.Builder<String, Version> versions = ImmutableSortedMap.naturalOrder();
            for (String artifactId : artifactIds) {
                versions.put(
                        artifactId,
                        new Version(
                                artifactId,
                                props.getProperty(artifactId + PROP_VERSION),
                                parseIso8601(props.getProperty(artifactId + PROP_COMMIT_DATE)),
                                props.getProperty(artifactId + PROP_SHORT_COMMIT_HASH),
                                props.getProperty(artifactId + PROP_LONG_COMMIT_HASH),
                                props.getProperty(artifactId + PROP_REPO_STATUS)));
            }

            return versions.build();
        });
    }

    private static long parseIso8601(String value) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z").parse(value).getTime();
        } catch (ParseException ignored) {
            return 0;
        }
    }

    private final String artifactId;
    private final String artifactVersion;
    private final long commitTimeMillis;
    private final String shortCommitHash;
    private final String longCommitHash;
    private final String repositoryStatus;

    private Version(
            String artifactId, String artifactVersion, long commitTimeMillis,
            String shortCommitHash, String longCommitHash, String repositoryStatus) {
        this.artifactId = artifactId;
        this.artifactVersion = artifactVersion;
        this.commitTimeMillis = commitTimeMillis;
        this.shortCommitHash = shortCommitHash;
        this.longCommitHash = longCommitHash;
        this.repositoryStatus = repositoryStatus;
    }

    /**
     * Returns the Maven artifact ID of the component, such as {@code "armeria-grpc"}.
     */
    @JsonProperty
    public String artifactId() {
        return artifactId;
    }

    /**
     * Returns the Maven artifact version of the component, such as {@code "1.0.0"}.
     */
    @JsonProperty
    public String artifactVersion() {
        return artifactVersion;
    }

    /**
     * Returns when the release commit was created.
     */
    @JsonProperty
    public long commitTimeMillis() {
        return commitTimeMillis;
    }

    /**
     * Returns the short hash of the release commit.
     */
    @JsonProperty
    public String shortCommitHash() {
        return shortCommitHash;
    }

    /**
     * Returns the long hash of the release commit.
     */
    @JsonProperty
    public String longCommitHash() {
        return longCommitHash;
    }

    /**
     * Returns the status of the repository when performing the release process.
     *
     * @return {@code "clean"} if the repository was clean. {@code "dirty"} otherwise.
     */
    @JsonProperty
    public String repositoryStatus() {
        return repositoryStatus;
    }

    /**
     * Returns whether the repository was clean when performing the release process.
     * This method is a shortcut for {@code "clean".equals(repositoryStatus())}.
     */
    @JsonIgnore
    public boolean isRepositoryClean() {
        return "clean".equals(repositoryStatus);
    }

    @Override
    public String toString() {
        return artifactId + '-' + artifactVersion + '.' + shortCommitHash +
               (isRepositoryClean() ? "" : "(repository: " + repositoryStatus + ')');
    }
}
