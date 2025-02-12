package example.armeria.grpc.envoy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;

import com.github.dockerjava.api.command.InspectContainerResponse;

import com.linecorp.armeria.common.annotation.Nullable;

// https://github.com/envoyproxy/java-control-plane/blob/eaca1a4380e53b4b6339db4e9ffe0ada5e0b7f8f/server/src/test/java/io/envoyproxy/controlplane/server/EnvoyContainer.java
class EnvoyContainer extends GenericContainer<EnvoyContainer> {

    private static final Logger LOGGER = LoggerFactory.getLogger(EnvoyContainer.class);

    private static final String CONFIG_DEST = "/etc/envoy/envoy.yaml";
    private static final String LAUNCH_ENVOY_SCRIPT = "envoy/launch_envoy.sh";
    private static final String LAUNCH_ENVOY_SCRIPT_DEST = "/usr/local/bin/launch_envoy.sh";

    static final int ADMIN_PORT = 9901;

    private final String config;
    @Nullable
    private final String sedCommand;

    /**
     * A {@link GenericContainer} implementation for envoy containers.
     *
     * @param sedCommand optional sed command which may be used to postprocess the provided {@param config}.
     *                   This parameter will be fed into the command {@code sed -e <sedCommand>}.
     *                   An example command may be {@code "s/foo/bar/g;s/abc/def/g"}.
     */
    EnvoyContainer(String config, @Nullable String sedCommand) {
        super("envoyproxy/envoy:v1.30.1");
        this.config = config;
        this.sedCommand = sedCommand;
    }

    @Override
    protected void configure() {
        super.configure();

        withClasspathResourceMapping(LAUNCH_ENVOY_SCRIPT, LAUNCH_ENVOY_SCRIPT_DEST, BindMode.READ_ONLY);
        withClasspathResourceMapping(config, CONFIG_DEST, BindMode.READ_ONLY);

        if (sedCommand != null) {
            withCommand("/bin/bash", "/usr/local/bin/launch_envoy.sh",
                        sedCommand, CONFIG_DEST, "-l", "debug");
        }

        addExposedPort(ADMIN_PORT);
    }

    @Override
    protected void containerIsStarting(InspectContainerResponse containerInfo) {
        followOutput(new Slf4jLogConsumer(LOGGER).withPrefix("ENVOY"));

        super.containerIsStarting(containerInfo);
    }
}
