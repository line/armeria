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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.ServiceInvocationContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * A skeletal {@link PathMapping} implementation. Implement {@link #doApply(String)}.
 */
public abstract class AbstractPathMapping implements PathMapping {

    /**
     * Matches the specified {@code path} and translates the matched {@code path} to another path string.
     * This method performs sanity checks on the specified {@code path}, calls {@link #doApply(String)},
     * and then performs sanity checks on the returned {@code mappedPath}.
     *
     * @param path an absolute path as defined in <a href="https://tools.ietf.org/html/rfc3986">RFC3986</a>
     * @return the translated path which is used as the value of {@link ServiceInvocationContext#mappedPath()}.
     *         {@code null} if the specified {@code path} does not match this mapping.
     */
    @Override
    public final String apply(String path) {
        requireNonNull(path, "path");
        if (path.isEmpty() || path.charAt(0) != '/') {
            throw new IllegalArgumentException("path: " + path + " (expected: an absolute path)");
        }

        final String mappedPath = doApply(path);
        if (mappedPath == null) {
            return null;
        }

        if (mappedPath.isEmpty()) {
            throw new IllegalStateException("mapped path is not an absolute path: <empty>");
        }

        if (mappedPath.charAt(0) != '/') {
            throw new IllegalStateException("mapped path is not an absolute path: " + mappedPath);
        }

        return mappedPath;
    }

    /**
     * Invoked by {@link #apply(String)} to perform the actual path matching and translation.
     *
     * @param path an absolute path as defined in <a href="https://tools.ietf.org/html/rfc3986">RFC3986</a>
     * @return the translated path which is used as the value of {@link ServiceInvocationContext#mappedPath()}.
     *         {@code null} if the specified {@code path} does not match this mapping.
     */
    protected abstract String doApply(String path);



    /**
     * A helper method which maps the variable symbols on {@code routingRule} with the values from {@code path}.
     * Supports 'variable symbol' and '*' rules, mostly inspired from http://www.sinatrarb.com/intro.html#Routes
     *
     * @param routingRule a routing rule that might contains some variables
     * @param path a requested URI path
     * @return a {@link HashMap} which maps the variable symbol and the value
     *         {@code null} if the given {@code path} is not suitable for the given {@code routingRule}
     */
    protected final HashMap<String, List<String>> getRoutingVariables(String routingRule, String path) {
        /**
         * Calculate the mapping validity between {@code routingRule} and {@code path} by dynamic programming
         * {@code isValid[i1][i2]} is true if {@code routingRule[i1:]} is matched with {@code path[i2:]}
         */
        int rlen = routingRule.length(), plen = path.length();
        boolean[][] isValid = new boolean[rlen + 1][plen + 1];
        int[][][] transition = new int[rlen + 1][plen + 1][2];

        isValid[rlen][plen] = true;
        for (int ruleIndex = rlen - 1; ruleIndex >= 0; ruleIndex--) {
            for (int pathIndex = plen - 1; pathIndex >= 0; pathIndex--) {
                char currentRule = routingRule.charAt(ruleIndex);
                char currentPath = path.charAt(pathIndex);
                String currentTwoRule = "";
                if (ruleIndex + 2 <= rlen) {
                    currentTwoRule = routingRule.substring(ruleIndex, ruleIndex + 2);
                }

                if (currentTwoRule.equals("/:")) {  // rule for 'variable symbol'
                    if (currentPath != '/') continue;
                    if (ruleIndex + 2 >= rlen) continue;
                    if (pathIndex + 1 == plen) continue;

                    char nextRule = routingRule.charAt(ruleIndex + 2);
                    char nextPath = path.charAt(pathIndex + 1);
                    if (nextRule == '/' || nextRule == '*' || nextRule == ':') continue;
                    if (nextPath == '/') continue;

                    int nextRuleIndex = ruleIndex + 2;
                    while (nextRuleIndex < rlen && routingRule.charAt(nextRuleIndex) != '/') {
                        nextRuleIndex++;
                    }
                    int nextPathIndex = pathIndex + 1;
                    while (nextPathIndex < plen && path.charAt(nextPathIndex) != '/') {
                        nextPathIndex++;
                    }

                    isValid[ruleIndex][pathIndex] = isValid[nextRuleIndex][nextPathIndex];
                    transition[ruleIndex][pathIndex][0] = nextRuleIndex;
                    transition[ruleIndex][pathIndex][1] = nextPathIndex;
                } else if (currentRule == '*') {    // rule for '*'
                    for (int nextPathIndex = pathIndex; nextPathIndex <= plen; nextPathIndex++) {
                        if (isValid[ruleIndex + 1][nextPathIndex]) {
                            isValid[ruleIndex][pathIndex] = true;
                            transition[ruleIndex][pathIndex][0] = ruleIndex + 1;
                            transition[ruleIndex][pathIndex][1] = nextPathIndex;
                            break;
                        }
                    }
                } else if (currentRule == currentPath) {    // rule for exact matching
                    isValid[ruleIndex][pathIndex] = isValid[ruleIndex + 1][pathIndex + 1];
                    transition[ruleIndex][pathIndex][0] = ruleIndex + 1;
                    transition[ruleIndex][pathIndex][1] = pathIndex + 1;
                }
            }
        }

        if(!isValid[0][0]) {
            return null;
        }

        // only glob may have multiple variables, e.g. "/foo/*/bar/*/hello"
        HashMap<String, List<String>> ret = new HashMap<>();
        ret.put("*", new ArrayList<>());

        int ruleIndex = 0, pathIndex = 0;
        while (ruleIndex < rlen) {
            int nextRuleIndex = transition[ruleIndex][pathIndex][0];
            int nextPathIndex = transition[ruleIndex][pathIndex][1];

            boolean isVariable = ruleIndex + 2 < rlen && routingRule.substring(ruleIndex, ruleIndex + 2).equals("/:");
            boolean isGlob = routingRule.charAt(ruleIndex) == '*';
            if (isVariable) {
                String symbol = routingRule.substring(ruleIndex + 2, nextRuleIndex);
                String value = path.substring(pathIndex + 1, nextPathIndex);
                ArrayList<String> values = new ArrayList<>();
                values.add(value);
                ret.put(symbol, values);
            } else if (isGlob) {
                ret.get("*").add(path.substring(pathIndex, nextPathIndex));
            }

            ruleIndex = nextRuleIndex;
            pathIndex = nextPathIndex;
        }

        return ret;
    }
}
