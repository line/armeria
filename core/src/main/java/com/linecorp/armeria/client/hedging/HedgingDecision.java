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

package com.linecorp.armeria.client.hedging;

import static com.google.common.base.Preconditions.checkArgument;

public final class HedgingDecision {
    public static final long NO_HEDGING_DELAY_MILLIS = -1;
    private static final HedgingDecision NO_HEDGING =
            new HedgingDecision(NO_HEDGING_DELAY_MILLIS);
    private static final HedgingDecision NEXT = new HedgingDecision(NO_HEDGING_DELAY_MILLIS);

    public static HedgingDecision hedge(long hedgingDelayMillis) {
        checkArgument(hedgingDelayMillis >= 0,
                      "hedgingDelayMillis: %s (expected: >= 0)", hedgingDelayMillis);

        return new HedgingDecision(hedgingDelayMillis);
    }

    public static HedgingDecision noHedge() {
        return NO_HEDGING;
    }

    public static HedgingDecision next() {
        return NEXT;
    }

    private final long hedgingDelayMillis;

    private HedgingDecision(long hedgingDelayMillis) {
        this.hedgingDelayMillis = hedgingDelayMillis;
    }

    long hedgingDelayMillis() {
        return hedgingDelayMillis;
    }

    @Override
    public String toString() {
        if (this == NO_HEDGING) {
            return "HedgingDecision(NO_RETRY)";
        } else if (this == NEXT) {
            return "HedgingDecision(NEXT)";
        } else {
            return "HedgingDecision(HEDGE(" + hedgingDelayMillis + "))";
        }
    }
}
