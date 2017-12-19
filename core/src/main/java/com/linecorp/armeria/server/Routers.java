/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.server;

import static com.linecorp.armeria.server.RouteCache.wrapCompositeServiceRouter;
import static com.linecorp.armeria.server.RouteCache.wrapVirtualHostRouter;
import static java.util.Objects.requireNonNull;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.server.RoutingTrie.Builder;
import com.linecorp.armeria.server.composition.CompositeServiceEntry;

/**
 * A factory that creates a {@link Router} instance.
 */
public final class Routers {
    private static final Logger logger = LoggerFactory.getLogger(Routers.class);

    /**
     * Returns the default implementation of the {@link Router} to find a {@link ServiceConfig}.
     * It consists of several router implementations which use one of Trie and List. It also includes
     * cache mechanism to improve its performance.
     */
    public static Router<ServiceConfig> ofVirtualHost(Iterable<ServiceConfig> configs) {
        requireNonNull(configs, "configs");
        return wrapVirtualHostRouter(defaultRouter(configs, ServiceConfig::pathMapping));
    }

    /**
     * Returns the default implementation of the {@link Router} to find a {@link CompositeServiceEntry}.
     */
    public static <I extends Request, O extends Response> Router<Service<I, O>> ofCompositeService(
            List<CompositeServiceEntry<I, O>> entries) {
        requireNonNull(entries, "entries");

        final Router<CompositeServiceEntry<I, O>> delegate =
                wrapCompositeServiceRouter(defaultRouter(entries, CompositeServiceEntry::pathMapping));
        return new CompositeRouter<>(delegate, result ->
                result.isPresent() ? PathMapped.of(result.mapping(), result.mappingResult(),
                                                   result.value().service())
                                   : PathMapped.empty());
    }

    /**
     * Returns the default implementation of {@link Router}. It consists of several router implementations
     * which use one of Trie and List. Consecutive {@link ServiceConfig}s would be grouped according to whether
     * it is able to produce trie path string or not while traversing the list, then each group would be
     * transformed to a {@link Router}.
     */
    private static <V> Router<V> defaultRouter(Iterable<V> values,
                                               Function<V, PathMapping> pathMappingResolver) {
        return new CompositeRouter<>(routers(values, pathMappingResolver), Function.identity());
    }

    /**
     * Returns a list of {@link Router}s.
     */
    @VisibleForTesting
    static <V> List<Router<V>> routers(Iterable<V> values, Function<V, PathMapping> pathMappingResolver) {
        final ImmutableList.Builder<Router<V>> builder = ImmutableList.builder();
        final List<V> group = new ArrayList<>();

        boolean addingTrie = true;

        for (V value : values) {
            final PathMapping mapping = pathMappingResolver.apply(value);
            final boolean triePathPresent = mapping.triePath().isPresent();
            if (addingTrie && triePathPresent || !addingTrie && !triePathPresent) {
                // We are adding the same type of PathMapping to 'group'.
                group.add(value);
                continue;
            }

            // Changed the router type.
            if (!group.isEmpty()) {
                builder.add(router(addingTrie, group, pathMappingResolver));
            }
            addingTrie = !addingTrie;
            group.add(value);
        }
        if (!group.isEmpty()) {
            builder.add(router(addingTrie, group, pathMappingResolver));
        }
        return builder.build();
    }

    /**
     * Returns a {@link Router} implementation which is using one of {@link RoutingTrie} and {@link List}.
     */
    private static <V> Router<V> router(boolean isTrie, List<V> values,
                                        Function<V, PathMapping> pathMappingResolver) {
        final Comparator<V> valueComparator =
                Comparator.comparingInt(e -> -1 * pathMappingResolver.apply(e).complexity());

        final Router<V> router;
        if (isTrie) {
            final RoutingTrie.Builder<V> builder = new Builder<>();
            // Set a comparator to sort services by the number of conditions to be checked in a descending
            // order.
            builder.comparator(valueComparator);
            values.forEach(v -> builder.add(pathMappingResolver.apply(v).triePath().get(), v));
            router = new TrieRouter<>(builder.build(), pathMappingResolver);
        } else {
            values.sort(valueComparator);
            router = new SequentialRouter<>(values, pathMappingResolver);
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Router created for {} service(s): {}",
                         values.size(), router.getClass().getSimpleName());
            values.forEach(c -> {
                final PathMapping mapping = pathMappingResolver.apply(c);
                logger.debug("meterTag: {}, complexity: {}", mapping.meterTag(), mapping.complexity());
            });
        }
        values.clear();
        return router;
    }

    /**
     * Finds the most suitable service from the given {@link ServiceConfig} list.
     */
    private static <V> PathMapped<V> findsBest(PathMappingContext mappingCtx, List<V> values,
                                               Function<V, PathMapping> pathMappingResolver) {
        PathMapped<V> result = PathMapped.empty();
        if (values != null) {
            for (V value : values) {
                final PathMapping mapping = pathMappingResolver.apply(value);
                final PathMappingResult mappingResult = mapping.apply(mappingCtx);
                if (mappingResult.isPresent()) {
                    //
                    // The services are sorted as follows:
                    //
                    // 1) annotated service with method and media type negotiation
                    //    (consumable and producible)
                    // 2) annotated service with method and producible media type negotiation
                    // 3) annotated service with method and consumable media type negotiation
                    // 4) annotated service with method negotiation
                    // 5) the other services (in a registered order)
                    //
                    // 1) and 2) may produce a score between the lowest and the highest because they should
                    // negotiate the produce type with the value of 'Accept' header.
                    // 3), 4) and 5) always produces the lowest score.
                    //

                    // Found the best matching.
                    if (mappingResult.hasHighestScore()) {
                        result = PathMapped.of(mapping, mappingResult, value);
                        break;
                    }

                    // This means that the 'mappingResult' is produced by one of 3), 4) and 5).
                    // So we have no more chance to find a better matching from now.
                    if (mappingResult.hasLowestScore()) {
                        if (!result.isPresent()) {
                            result = PathMapped.of(mapping, mappingResult, value);
                        }
                        break;
                    }

                    // We have still a chance to find a better matching.
                    if (result.isPresent()) {
                        if (mappingResult.score() > result.mappingResult().score()) {
                            // Replace the candidate with the new one only if the score is better.
                            // If the score is same, we respect the order of service registration.
                            result = PathMapped.of(mapping, mappingResult, value);
                        }
                    } else {
                        // Keep the result as a candidate.
                        result = PathMapped.of(mapping, mappingResult, value);
                    }
                }
            }
        }
        return result;
    }

    private static final class TrieRouter<V> implements Router<V> {

        private final RoutingTrie<V> trie;
        private final Function<V, PathMapping> pathMappingResolver;

        TrieRouter(RoutingTrie<V> trie, Function<V, PathMapping> pathMappingResolver) {
            this.trie = requireNonNull(trie, "trie");
            this.pathMappingResolver = requireNonNull(pathMappingResolver, "pathMappingResolver");
        }

        @Override
        public PathMapped<V> find(PathMappingContext mappingCtx) {
            return findsBest(mappingCtx, trie.find(mappingCtx.path()), pathMappingResolver);
        }

        @Override
        public void dump(OutputStream output) {
            trie.dump(output);
        }
    }

    private static final class SequentialRouter<V> implements Router<V> {

        private final List<V> values;
        private final Function<V, PathMapping> pathMappingResolver;

        SequentialRouter(List<V> values, Function<V, PathMapping> pathMappingResolver) {
            this.values = ImmutableList.copyOf(requireNonNull(values, "values"));
            this.pathMappingResolver = requireNonNull(pathMappingResolver, "pathMappingResolver");
        }

        @Override
        public PathMapped<V> find(PathMappingContext mappingCtx) {
            return findsBest(mappingCtx, values, pathMappingResolver);
        }

        @Override
        public void dump(OutputStream output) {
            // Do not close this writer in order to keep output stream open.
            PrintWriter p = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
            p.printf("Dump of %s:%n", this);
            for (int i = 0; i < values.size(); i++) {
                p.printf("<%d> %s%n", i, values.get(i));
            }
            p.flush();
        }
    }

    private Routers() {}
}
