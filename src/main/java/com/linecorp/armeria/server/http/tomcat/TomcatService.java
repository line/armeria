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

import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.catalina.LifecycleState;
import org.apache.catalina.Server;
import org.apache.catalina.Service;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.util.ServerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.server.http.HttpService;

/**
 * An {@link HttpService} that dispatches its requests to a web application running in an embedded
 * <a href="http://tomcat.apache.org/">Tomcat</a>.
 *
 * @see TomcatServiceBuilder
 */
public final class TomcatService extends HttpService {

    private static final Logger logger = LoggerFactory.getLogger(TomcatService.class);

    private static final int TOMCAT_MAJOR_VERSION;

    static {
        final Pattern pattern = Pattern.compile("^([1-9][0-9]*)\\.");
        final String version = ServerInfo.getServerNumber();
        final Matcher matcher = pattern.matcher(version);
        int tomcatMajorVersion = -1;
        if (matcher.find()) {
            try {
                tomcatMajorVersion = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                // Probably greater than Integer.MAX_VALUE
            }
        }

        TOMCAT_MAJOR_VERSION = tomcatMajorVersion;
        if (TOMCAT_MAJOR_VERSION > 0) {
            logger.info("Tomcat version: {} (major: {})", version, TOMCAT_MAJOR_VERSION);
        } else {
            logger.info("Tomcat version: {} (major: unknown)", version);
        }
    }

    /**
     * Creates a new {@link TomcatService} with the web application at the root directory inside the
     * JAR/WAR/directory where the caller class is located at.
     */
    public static TomcatService forCurrentClassPath() {
        return TomcatServiceBuilder.forCurrentClassPath(3).build();
    }

    /**
     * Creates a new {@link TomcatService} with the web application at the specified document base directory
     * inside the JAR/WAR/directory where the caller class is located at.
     */
    public static TomcatService forCurrentClassPath(String docBase) {
        return TomcatServiceBuilder.forCurrentClassPath(docBase, 3).build();
    }

    /**
     * Creates a new {@link TomcatService} with the web application at the root directory inside the
     * JAR/WAR/directory where the specified class is located at.
     */
    public static TomcatService forClassPath(Class<?> clazz) {
        return TomcatServiceBuilder.forClassPath(clazz).build();
    }

    /**
     * Creates a new {@link TomcatService} with the web application at the specified document base directory
     * inside the JAR/WAR/directory where the specified class is located at.
     */
    public static TomcatService forClassPath(Class<?> clazz, String docBase) {
        return TomcatServiceBuilder.forClassPath(clazz, docBase).build();
    }

    /**
     * Creates a new {@link TomcatService} with the web application at the specified document base, which can
     * be a directory or a JAR/WAR file.
     */
    public static TomcatService forFileSystem(String docBase) {
        return TomcatServiceBuilder.forFileSystem(docBase).build();
    }

    /**
     * Creates a new {@link TomcatService} with the web application at the specified document base, which can
     * be a directory or a JAR/WAR file.
     */
    public static TomcatService forFileSystem(Path docBase) {
        return TomcatServiceBuilder.forFileSystem(docBase).build();
    }

    /**
     * Creates a new {@link TomcatService} from an existing {@link Tomcat} instance.
     * If the specified {@link Tomcat} instance is not configured properly, the returned {@link TomcatService}
     * may respond with '503 Service Not Available' error.
     */
    public static TomcatService forTomcat(Tomcat tomcat) {
        requireNonNull(tomcat, "tomcat");

        final String hostname = tomcat.getEngine().getDefaultHost();
        if (hostname == null) {
            throw new IllegalArgumentException("default hostname not configured: " + tomcat);
        }

        final Connector connector = tomcat.getConnector();
        if (connector == null) {
            throw new IllegalArgumentException("connector not configured: " + tomcat);
        }

        return forConnector(hostname, connector);
    }

    /**
     * Creates a new {@link TomcatService} from an existing Tomcat {@link Connector} instance.
     * If the specified {@link Connector} instance is not configured properly, the returned
     * {@link TomcatService} may respond with '503 Service Not Available' error.
     */
    public static TomcatService forConnector(Connector connector) {
        requireNonNull(connector, "connector");
        return new TomcatService(null, hostname -> connector);
    }

    /**
     * Creates a new {@link TomcatService} from an existing Tomcat {@link Connector} instance.
     * If the specified {@link Connector} instance is not configured properly, the returned
     * {@link TomcatService} may respond with '503 Service Not Available' error.
     */
    public static TomcatService forConnector(String hostname, Connector connector) {
        requireNonNull(hostname, "hostname");
        requireNonNull(connector, "connector");

        return new TomcatService(hostname, h -> connector);
    }

    static TomcatService forConfig(TomcatServiceConfig config) {
        final Consumer<Connector> postStopTask = connector -> {
            final Server server = connector.getService().getServer();
            if (server.getState() == LifecycleState.STOPPED) {
                try {
                    logger.info("Destroying an embedded Tomcat: {}", toString(server));
                    server.destroy();
                } catch (Exception e) {
                    logger.warn("Failed to destroy an embedded Tomcat: {}", toString(server), e);
                }
            }
        };

        return new TomcatService(null, new ManagedConnectorFactory(config), postStopTask);
    }

    static String toString(Server server) {
        requireNonNull(server, "server");

        final Service[] services = server.findServices();
        final String serviceName;
        if (services.length == 0) {
            serviceName = "<unknown>";
        } else {
            serviceName = services[0].getName();
        }

        final StringBuilder buf = new StringBuilder(128);

        buf.append("(serviceName: ");
        buf.append(serviceName);
        if (TOMCAT_MAJOR_VERSION >= 8) {
            buf.append(", catalinaBase: " + server.getCatalinaBase());
        }
        buf.append(')');

        return buf.toString();
    }

    private TomcatService(String hostname, Function<String, Connector> connectorFactory) {
        this(hostname, connectorFactory, unused -> {});
    }

    private TomcatService(String hostname,
                  Function<String, Connector> connectorFactory, Consumer<Connector> postStopTask) {
        super(new TomcatServiceInvocationHandler(hostname, connectorFactory, postStopTask));
    }

    /**
     * Returns Tomcat {@link Connector}
     */
    public Connector connector() {
        return ((TomcatServiceInvocationHandler) handler()).connector();
    }
}
