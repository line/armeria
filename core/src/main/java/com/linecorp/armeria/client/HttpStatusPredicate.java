package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.util.function.Predicate;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.annotation.Nullable;

final class HttpStatusPredicate implements Predicate<HttpStatus> {

    @Nullable
    private HttpStatus status;
    @Nullable
    private HttpStatusClass statusClass;

    HttpStatusPredicate(HttpStatus status) {
        this.status = requireNonNull(status, "status");
    }

    HttpStatusPredicate(HttpStatusClass statusClass) {
        this.statusClass = requireNonNull(statusClass, "statusClass");
    }

    @Override
    public boolean test(HttpStatus status) {
        if (this.status != null) {
            return this.status.equals(status);
        }
        assert statusClass != null;
        return status.codeClass() == statusClass;
    }

    @Nullable
    HttpStatus status() {
        return status;
    }

    @Nullable
    HttpStatusClass statusClass() {
        return statusClass;
    }
}
