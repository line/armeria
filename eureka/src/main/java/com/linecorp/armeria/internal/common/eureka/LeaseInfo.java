/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.internal.common.eureka;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;
import com.google.common.base.Objects;

/**
 * A lease information of an instance in Eureka.
 * See <a href="https://github.com/Netflix/eureka/wiki/Understanding-eureka-client-server-communication#renew">
 * renew</a>.
 */
@JsonRootName("leaseInfo")
@JsonInclude(Include.NON_DEFAULT)
public final class LeaseInfo {

    private final int renewalIntervalInSecs;
    private final int durationInSecs;

    // These properties are populated by the Eureka registry.
    private final long registrationTimestamp;
    private final long lastRenewalTimestamp;
    private final long evictionTimestamp;
    private final long serviceUpTimestamp;

    /**
     * Creates a new instance.
     */
    public LeaseInfo(int renewalIntervalInSecs, int durationInSecs) {
        this(renewalIntervalInSecs, durationInSecs, 0, 0, 0, 0);
    }

    /**
     * Creates a new instance.
     */
    public LeaseInfo(@JsonProperty("renewalIntervalInSecs") int renewalIntervalInSecs,
                     @JsonProperty("durationInSecs") int durationInSecs,
                     @JsonProperty("registrationTimestamp") long registrationTimestamp,
                     @JsonProperty("lastRenewalTimestamp") long lastRenewalTimestamp,
                     @JsonProperty("evictionTimestamp") long evictionTimestamp,
                     @JsonProperty("serviceUpTimestamp") long serviceUpTimestamp) {
        checkArgument(renewalIntervalInSecs > 0,
                      "renewalIntervalInSecs: %s (expected: > 0)", renewalIntervalInSecs);
        checkArgument(durationInSecs > 0, "durationInSecs: %s (expected: > 0)", durationInSecs);
        checkArgument(registrationTimestamp >= 0,
                      "registrationTimestamp: %s (expected: >= 0)", registrationTimestamp);
        checkArgument(lastRenewalTimestamp >= 0,
                      "lastRenewalTimestamp: %s (expected: >= 0)", lastRenewalTimestamp);
        checkArgument(evictionTimestamp >= 0,
                      "evictionTimestamp: %s (expected: >= 0)", evictionTimestamp);
        checkArgument(serviceUpTimestamp >= 0,
                      "serviceUpTimestamp: %s (expected: >= 0)", serviceUpTimestamp);
        this.renewalIntervalInSecs = renewalIntervalInSecs;
        this.durationInSecs = durationInSecs;
        this.registrationTimestamp = registrationTimestamp;
        this.lastRenewalTimestamp = lastRenewalTimestamp;
        this.evictionTimestamp = evictionTimestamp;
        this.serviceUpTimestamp = serviceUpTimestamp;
    }

    /**
     * Returns the interval between renewal in seconds. See
     * <a href="https://github.com/Netflix/eureka/wiki/Understanding-eureka-client-server-communication#renew">
     * renew</a>.
     */
    @JsonProperty
    public int getRenewalIntervalInSecs() {
        return renewalIntervalInSecs;
    }

    /**
     * Returns the lease duration in seconds. See
     * <a href="https://github.com/Netflix/eureka/wiki/Understanding-eureka-client-server-communication#renew">
     * renew</a>.
     */
    @JsonProperty
    public int getDurationInSecs() {
        return durationInSecs;
    }

    /**
     * Returns the registration timestamp represented as the number of milliseconds since the epoch.
     */
    @JsonProperty
    public long getRegistrationTimestamp() {
        return registrationTimestamp;
    }

    /**
     * Returns the last renewal timestamp of lease represented as the number of milliseconds since the epoch.
     */
    @JsonProperty
    public long getLastRenewalTimestamp() {
        return lastRenewalTimestamp;
    }

    /**
     * Returns the deregistration timestamp represented as the number of milliseconds since the epoch.
     */
    @JsonProperty
    public long getEvictionTimestamp() {
        return evictionTimestamp;
    }

    /**
     * Returns the service UP timestamp represented as the number of milliseconds since the epoch.
     */
    @JsonProperty
    public long getServiceUpTimestamp() {
        return serviceUpTimestamp;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LeaseInfo)) {
            return false;
        }
        final LeaseInfo leaseInfo = (LeaseInfo) o;
        return renewalIntervalInSecs == leaseInfo.renewalIntervalInSecs &&
               durationInSecs == leaseInfo.durationInSecs &&
               registrationTimestamp == leaseInfo.registrationTimestamp &&
               lastRenewalTimestamp == leaseInfo.lastRenewalTimestamp &&
               evictionTimestamp == leaseInfo.evictionTimestamp &&
               serviceUpTimestamp == leaseInfo.serviceUpTimestamp;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(renewalIntervalInSecs, durationInSecs, registrationTimestamp,
                                lastRenewalTimestamp, evictionTimestamp, serviceUpTimestamp);
    }

    @Override
    public String toString() {
        return toStringHelper(this).add("renewalIntervalInSecs", renewalIntervalInSecs)
                                   .add("durationInSecs", durationInSecs)
                                   .add("registrationTimestamp", registrationTimestamp)
                                   .add("lastRenewalTimestamp", lastRenewalTimestamp)
                                   .add("evictionTimestamp", evictionTimestamp)
                                   .add("serviceUpTimestamp", serviceUpTimestamp)
                                   .toString();
    }
}
