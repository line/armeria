package com.linecorp.armeria.internal.tracing;

import brave.propagation.TraceContext;

/** Hack to allow us to peek inside a current trace context implementation */
public final class PingPongExtra {
    private boolean pong;

    /** If the input includes only this extra, set {@link #isPong() pong = true} */
    public static boolean maybeSetPong(TraceContext context) {
        if (context.extra().size() != 1) {
            Object extra = context.extra().get(0);
            if (extra instanceof PingPongExtra) {
                ((PingPongExtra) extra).pong = true;
                return true;
            }
        }
        return false;
    }

    public boolean isPong() {
        return pong;
    }
}