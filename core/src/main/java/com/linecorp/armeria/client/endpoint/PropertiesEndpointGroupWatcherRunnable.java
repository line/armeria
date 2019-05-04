package com.linecorp.armeria.client.endpoint;

import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

public class PropertiesEndpointGroupWatcherRunnable implements Runnable {
    final WatchService watchService;
    private final Map<String, RunnableGroupContext> contextMap = new HashMap<>();

    PropertiesEndpointGroupWatcherRunnable() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            throw new RuntimeException("Failed to register watch service");
        }
    }

    public void registerEndpointGroup(URL resourceUrl, PropertiesEndpointGroupWatcherReloader reloader) {
        final File file = new File(resourceUrl.getFile());
        final Path path = file.getParentFile().toPath();
        try {
            final WatchKey key = path.register(watchService, ENTRY_MODIFY); // can we register multiple paths?
            contextMap.put(resourceUrl.getFile(), new RunnableGroupContext(key, reloader));
        } catch (IOException e) {
            throw new RuntimeException("Failed to register path");
        }
    }

    public void deregisterResourceUrl(@Nullable URL resourceUrl) {
        final RunnableGroupContext context = contextMap.remove(resourceUrl.getFile());
        context.key.cancel();
        contextMap.remove(resourceUrl.getFile());
    }

    @Override
    public void run() {
        try {
            WatchKey key;
            while ((key = watchService.take()) != null) {
                final Set<String> targetFileNames = contextMap.keySet();
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == ENTRY_MODIFY) {
                        @SuppressWarnings("unchecked")
                        final Path watchedPath = ((Path) key.watchable()).resolve(((WatchEvent<Path>) event).context());
                        final String watchedPathFile = watchedPath.toFile().getAbsolutePath();
                        if (targetFileNames.contains(watchedPathFile)) {
                            final RunnableGroupContext context = contextMap.get(watchedPathFile);
                            context.reloader.reload();
                        }
                    }
                }
                key.reset();
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Unexpected exception while watching");
        }
    }

    private static class RunnableGroupContext {
        private final WatchKey key;
        private final PropertiesEndpointGroupWatcherReloader reloader;
        RunnableGroupContext(WatchKey key, PropertiesEndpointGroupWatcherReloader reloader) {
            this.key = key;
            this.reloader = reloader;
        }
    }

    @FunctionalInterface
    interface PropertiesEndpointGroupWatcherReloader {
        void reload();
    }

    public boolean isEmpty() {
        return contextMap.isEmpty();
    }
}
