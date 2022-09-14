/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.client.circuitbreaker;

import static com.google.common.base.Preconditions.checkState;

import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * An abstract setter for {@link CircuitBreakerRule} and {@link CircuitBreakerRuleWithContent}.
 */
@UnstableApi
public abstract class CircuitBreakerRuleSetter<O extends Response> {

    @Nullable
    private final CircuitBreakerRule rule;

    @Nullable
    private final CircuitBreakerRuleWithContent<O> ruleWithContent;

    /**
     * Creates a new setter with the specified {@link CircuitBreakerRuleWithContent}
     * and {@link CircuitBreakerRule}.
     */
    protected CircuitBreakerRuleSetter(
            @Nullable CircuitBreakerRule rule,
            @Nullable CircuitBreakerRuleWithContent<O> ruleWithContent) {
        this.rule = rule;
        this.ruleWithContent = ruleWithContent;
    }

    /**
     * Returns the {@link CircuitBreakerRule} set for this builder.
     */
    protected final CircuitBreakerRule rule() {
        checkState(rule != null, "rule is not set.");
        return rule;
    }

    /**
     * Returns the {@link CircuitBreakerRuleWithContent} set for this builder.
     */
    protected final CircuitBreakerRuleWithContent<O> ruleWithContent() {
        checkState(ruleWithContent != null, "ruleWithContent is not set.");
        return ruleWithContent;
    }

    /**
     * Returns the {@link CircuitBreakerRule} set for this builder without any validation.
     */
    @Nullable
    protected CircuitBreakerRule rawRule() {
        return rule;
    }

    /**
     * Returns the {@link CircuitBreakerRuleWithContent} set for this builder without any validation.
     */
    @Nullable
    protected CircuitBreakerRuleWithContent<O> rawRuleWithContent() {
        return ruleWithContent;
    }
}
