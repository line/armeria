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

package com.linecorp.armeria.xds;

import java.util.List;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ConnectionContext;

/**
 * Selects the best-matching {@link FilterChainSnapshot} for a {@link ConnectionContext}
 * using a BFS-style multi-pass narrowing algorithm that replicates Envoy's
 * <a href="https://github.com/envoyproxy/envoy/blob/74ef415825d391edc129afd17d2cb640abe785e9/source/common/listener_manager/filter_chain_manager_impl.cc#L543">
 * filter chain matching</a> semantics.
 *
 * <p>At each priority level the candidate list is narrowed: if any candidate specifies
 * a value for that criterion and the value matches the connection, only those candidates
 * survive; otherwise the wildcard (unset) candidates are kept. If specific candidates
 * exist but none match, the wildcards act as fallback. When all candidates are eliminated
 * the default filter chain is returned.
 *
 * <p>Priority order (highest to lowest):
 * <ol>
 *   <li>{@code destination_port}</li>
 *   <li>{@code prefix_ranges} (destination IP)</li>
 *   <li>{@code server_names}</li>
 *   <li>{@code transport_protocol}</li>
 *   <li>{@code application_protocols}</li>
 *   <li>{@code direct_source_prefix_ranges}</li>
 *   <li>{@code source_type}</li>
 *   <li>{@code source_prefix_ranges}</li>
 *   <li>{@code source_ports}</li>
 * </ol>
 *
 * <p>Each {@link NarrowStep} precomputes the wildcard/specific partition of the full chain
 * list. Levels where all chains are wildcard are skipped entirely at match time. When the
 * candidate list has not been narrowed by a prior step (reference equality with the full
 * list), the precomputed partition is reused to avoid redundant iteration.
 */
final class FilterChainMatcher {

    private abstract static class NarrowStep {

        private final List<FilterChainSnapshot> allChains;
        private final ImmutableList<FilterChainSnapshot> precomputedSpecifics;
        private final ImmutableList<FilterChainSnapshot> precomputedWildcards;

        NarrowStep(List<FilterChainSnapshot> allChains) {
            this.allChains = allChains;
            final ImmutableList.Builder<FilterChainSnapshot> specifics = ImmutableList.builder();
            final ImmutableList.Builder<FilterChainSnapshot> wildcards = ImmutableList.builder();
            for (FilterChainSnapshot fcs : allChains) {
                if (isSpecific(fcs)) {
                    specifics.add(fcs);
                } else {
                    wildcards.add(fcs);
                }
            }
            precomputedSpecifics = specifics.build();
            precomputedWildcards = wildcards.build();
        }

        abstract boolean isSpecific(FilterChainSnapshot fcs);

        abstract List<FilterChainSnapshot> matchSpecifics(List<FilterChainSnapshot> specifics,
                                                          ConnectionContext ctx);

        final List<FilterChainSnapshot> narrow(List<FilterChainSnapshot> candidates,
                                               ConnectionContext ctx) {
            if (precomputedSpecifics.isEmpty()) {
                return candidates;
            }
            if (candidates != allChains) {
                return slowPath(candidates, ctx);
            }
            final List<FilterChainSnapshot> matched = matchSpecifics(precomputedSpecifics, ctx);
            return matched.isEmpty() ? precomputedWildcards : matched;
        }

        private List<FilterChainSnapshot> slowPath(List<FilterChainSnapshot> candidates,
                                                   ConnectionContext ctx) {
            final ImmutableList.Builder<FilterChainSnapshot> specifics = ImmutableList.builder();
            final ImmutableList.Builder<FilterChainSnapshot> wildcards = ImmutableList.builder();
            for (FilterChainSnapshot fcs : candidates) {
                if (isSpecific(fcs)) {
                    specifics.add(fcs);
                } else {
                    wildcards.add(fcs);
                }
            }
            final List<FilterChainSnapshot> matched = matchSpecifics(specifics.build(), ctx);
            return matched.isEmpty() ? wildcards.build() : matched;
        }
    }

    private final ImmutableList<FilterChainSnapshot> allChains;
    private final ImmutableList<NarrowStep> steps;

    FilterChainMatcher(List<FilterChainSnapshot> filterChains) {
        allChains = ImmutableList.copyOf(filterChains);
        // Steps are in priority order. Skipped upstream levels (2, 6-9) are omitted.
        // 2. prefix_ranges (destination IP) — skipped. Envoy narrows by longest CIDR prefix match.
        // 6. direct_source_prefix_ranges — skipped. Envoy narrows by CIDR match on direct remote address.
        // 7. source_type — skipped. Envoy narrows by ANY/SAME_IP_OR_LOOPBACK/EXTERNAL.
        // 8. source_prefix_ranges — skipped. Envoy narrows by CIDR match on remote address.
        // 9. source_ports — skipped. Envoy narrows by exact match on remote port.
        steps = ImmutableList.of(
                new DestinationPortStep(allChains),   // 1
                new ServerNamesStep(allChains),       // 3
                new TransportProtocolStep(allChains), // 4
                new ApplicationProtocolsStep(allChains) // 5
        );
    }

    @Nullable
    FilterChainSnapshot match(@Nullable FilterChainSnapshot defaultFilterChain,
                              ConnectionContext ctx) {
        List<FilterChainSnapshot> candidates = allChains;
        for (NarrowStep step : steps) {
            candidates = step.narrow(candidates, ctx);

            if (candidates.isEmpty()) {
                return defaultFilterChain;
            }
        }
        return candidates.get(0);
    }

    private static final class DestinationPortStep extends NarrowStep {

        DestinationPortStep(List<FilterChainSnapshot> allChains) {
            super(allChains);
        }

        @Override
        boolean isSpecific(FilterChainSnapshot fcs) {
            return fcs.filterChainMatch().hasDestinationPort();
        }

        @Override
        List<FilterChainSnapshot> matchSpecifics(List<FilterChainSnapshot> specifics,
                                                 ConnectionContext ctx) {
            final int port = ctx.localAddress().getPort();
            final ImmutableList.Builder<FilterChainSnapshot> matched = ImmutableList.builder();
            for (FilterChainSnapshot fcs : specifics) {
                if (fcs.filterChainMatch().getDestinationPort().getValue() == port) {
                    matched.add(fcs);
                }
            }
            return matched.build();
        }
    }

    // Exact-match only; wildcard domain suffix matching (e.g. *.example.com) is not implemented.
    private static final class ServerNamesStep extends NarrowStep {

        ServerNamesStep(List<FilterChainSnapshot> allChains) {
            super(allChains);
        }

        @Override
        boolean isSpecific(FilterChainSnapshot fcs) {
            return !fcs.filterChainMatch().getServerNamesList().isEmpty();
        }

        @Override
        List<FilterChainSnapshot> matchSpecifics(List<FilterChainSnapshot> specifics,
                                                 ConnectionContext ctx) {
            final String sniHostname = ctx.sniHostname();
            final ImmutableList.Builder<FilterChainSnapshot> matched = ImmutableList.builder();
            for (FilterChainSnapshot fcs : specifics) {
                if (sniHostname != null && !sniHostname.isEmpty() &&
                    fcs.filterChainMatch().getServerNamesList().contains(sniHostname)) {
                    matched.add(fcs);
                }
            }
            return matched.build();
        }
    }

    private static final class TransportProtocolStep extends NarrowStep {

        TransportProtocolStep(List<FilterChainSnapshot> allChains) {
            super(allChains);
        }

        @Override
        boolean isSpecific(FilterChainSnapshot fcs) {
            return !fcs.filterChainMatch().getTransportProtocol().isEmpty();
        }

        @Override
        List<FilterChainSnapshot> matchSpecifics(List<FilterChainSnapshot> specifics,
                                                 ConnectionContext ctx) {
            final String transportProtocol = ctx.sessionProtocol().isTls() ? "tls" : "raw_buffer";
            final ImmutableList.Builder<FilterChainSnapshot> matched = ImmutableList.builder();
            for (FilterChainSnapshot fcs : specifics) {
                if (fcs.filterChainMatch().getTransportProtocol().equals(transportProtocol)) {
                    matched.add(fcs);
                }
            }
            return matched.build();
        }
    }

    private static final class ApplicationProtocolsStep extends NarrowStep {

        ApplicationProtocolsStep(List<FilterChainSnapshot> allChains) {
            super(allChains);
        }

        @Override
        boolean isSpecific(FilterChainSnapshot fcs) {
            return !fcs.filterChainMatch().getApplicationProtocolsList().isEmpty();
        }

        @Override
        List<FilterChainSnapshot> matchSpecifics(List<FilterChainSnapshot> specifics,
                                                 ConnectionContext ctx) {
            final List<String> alpnProtocols = ctx.alpnProtocols();
            final ImmutableList.Builder<FilterChainSnapshot> matched = ImmutableList.builder();
            for (FilterChainSnapshot fcs : specifics) {
                final List<String> matchAlpn = fcs.filterChainMatch().getApplicationProtocolsList();
                for (String offered : alpnProtocols) {
                    if (matchAlpn.contains(offered)) {
                        matched.add(fcs);
                        break;
                    }
                }
            }
            return matched.build();
        }
    }
}
