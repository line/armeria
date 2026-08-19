package example.armeria.xds;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.protobuf.Any;
import com.google.protobuf.GeneratedMessageV3;
import com.google.protobuf.util.JsonFormat;

import com.linecorp.armeria.xds.XdsResourceReader;
import com.linecorp.armeria.xds.XdsType;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;

final class XdsTemplateReader {

    private static final JsonFormat.Printer PROTO_PRINTER =
            JsonFormat.printer()
                      .usingTypeRegistry(XdsResourceReader.typeRegistry())
                      .preservingProtoFieldNames();

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    static final Map<String, List<String>> VALID_PROFILES = scanValidProfiles();

    private static Map<String, List<String>> scanValidProfiles() {
        try {
            final Path base = Path.of(XdsTemplateReader.class.getResource("/xds/").toURI());
            final Map<String, List<String>> map = new HashMap<>();
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(base, Files::isDirectory)) {
                for (Path dir : dirs) {
                    final String profile = dir.getFileName().toString();
                    try (DirectoryStream<Path> templates =
                                 Files.newDirectoryStream(dir, "*.template.yaml")) {
                        for (Path f : templates) {
                            final String name = f.getFileName().toString()
                                                 .replace(".template.yaml", "");
                            map.computeIfAbsent(name, k -> new ArrayList<>()).add(profile);
                        }
                    }
                }
            }
            map.values().forEach(l -> l.sort(null));
            return Map.copyOf(map);
        } catch (Exception e) {
            throw new RuntimeException("Failed to scan xDS templates", e);
        }
    }

    static Listener readListener(ProfileState state, String name, Map<String, String> vars) {
        final Map<String, String> allVars = new HashMap<>(vars);
        allVars.put("listener-name", name);
        return read(state, name, allVars, Listener.class);
    }

    static <T extends GeneratedMessageV3> T read(
            ProfileState state, String name, Map<String, String> vars, Class<T> type) {
        String yaml = readTemplate(state.profileFor(name) + '/' + name);
        for (var entry : vars.entrySet()) {
            yaml = yaml.replace("${" + entry.getKey() + '}', entry.getValue());
        }
        return XdsResourceReader.from(yaml, type);
    }

    static Bootstrap readBootstrap(String name, Map<String, String> vars) {
        String yaml = readTemplate(name);
        for (var entry : vars.entrySet()) {
            yaml = yaml.replace("${" + entry.getKey() + '}', entry.getValue());
        }
        return XdsResourceReader.from(yaml, Bootstrap.class);
    }

    static void writeDiscoveryResponse(Path path, String typeUrl,
                                       List<? extends GeneratedMessageV3> resources) {
        final DiscoveryResponse.Builder builder =
                DiscoveryResponse.newBuilder()
                                 .setTypeUrl(typeUrl)
                                 .setVersionInfo(String.valueOf(System.currentTimeMillis()));
        for (GeneratedMessageV3 resource : resources) {
            builder.addResources(Any.pack(resource, ""));
        }
        try {
            final String json = PROTO_PRINTER.print(builder.build());
            final JsonNode tree = JSON_MAPPER.readTree(json);
            final String yaml = YAML_MAPPER.writeValueAsString(tree);
            // Write to a temp file first, then atomically rename to avoid
            // the file watcher reading a partially-written file.
            final Path tmp = Files.createTempFile(path.getParent(), ".xds-", ".tmp");
            Files.writeString(tmp, yaml, StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING,
                       StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write: " + path, e);
        }
    }

    static DiscoveryResponse readDiscoveryResponse(Path path) {
        try {
            final String content = Files.readString(path, StandardCharsets.UTF_8);
            return XdsResourceReader.from(content, DiscoveryResponse.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read: " + path, e);
        }
    }

    static String printProtoYaml(GeneratedMessageV3 msg) {
        try {
            final String json = PROTO_PRINTER.print(msg);
            final JsonNode tree = JSON_MAPPER.readTree(json);
            return YAML_MAPPER.writeValueAsString(tree);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void applyProfile(Path configDir, ProfileState state, Map<String, String> vars) {
        for (String name : VALID_PROFILES.keySet()) {
            applyResource(configDir, state, name, vars);
        }
    }

    static void applyProfileForLayer(Path configDir, ProfileState state,
                                     Map<String, String> vars, int layer) {
        final String prefix = "layer" + layer + '-';
        for (String name : VALID_PROFILES.keySet()) {
            if (name.startsWith(prefix)) {
                applyResource(configDir, state, name, vars);
            }
        }
    }

    private static void applyResource(Path configDir, ProfileState state,
                                      String name, Map<String, String> vars) {
        if (!state.shouldUpdate(name)) {
            return;
        }
        if (name.endsWith("-listener")) {
            writeDiscoveryResponse(configDir.resolve(name + ".yaml"),
                                   XdsType.LISTENER.typeUrl(),
                                   List.of(readListener(state, name, vars)));
        } else if (name.endsWith("-cluster")) {
            writeDiscoveryResponse(configDir.resolve(name + ".yaml"),
                                   XdsType.CLUSTER.typeUrl(),
                                   List.of(read(state, name, vars, Cluster.class)));
        } else if (name.endsWith("-endpoints")) {
            writeDiscoveryResponse(configDir.resolve(name + ".yaml"),
                                   XdsType.ENDPOINT.typeUrl(),
                                   List.of(read(state, name, vars,
                                                ClusterLoadAssignment.class)));
        }
    }

    static Map<String, String> portVars(String prefix, Map<String, List<Integer>> ports) {
        final Map<String, String> vars = new HashMap<>();
        for (var entry : ports.entrySet()) {
            final String zone = entry.getKey();
            final List<Integer> zonePorts = entry.getValue();
            for (int i = 0; i < zonePorts.size(); i++) {
                vars.put(prefix + zone + "-port-" + (i + 1), String.valueOf(zonePorts.get(i)));
            }
        }
        return vars;
    }

    private static String readTemplate(String name) {
        final String path = "/xds/" + name + ".template.yaml";
        try (InputStream is = XdsTemplateReader.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalArgumentException("Template not found: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private XdsTemplateReader() {}
}
