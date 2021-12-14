
package cn.swiftpass.feynman.hessian.client;

import com.linecorp.armeria.client.ClientOption;

/**
 * {@link ClientOption}s to control hessian-specific behavior.
 */
public final class HessianClientOptions {

    /**
     * Enable hessian method overload. The default value is false
     */
    public static final ClientOption<Boolean> OVERLOAD_ENABLED = ClientOption.define("HESSIAN_OVERLOAD_ENABLED",
            Boolean.FALSE);

    /**
     * Use hessian2 request. The default value is true.
     */
    public static final ClientOption<Boolean> HESSIAN2_REQUEST = ClientOption.define("HESSIAN_HESSIAN2_REQUEST",
            Boolean.TRUE);

    /**
     * Use hessian2 reply . The default value is true.
     */
    public static final ClientOption<Boolean> HESSIAN2_REPLY = ClientOption.define("HESSIAN_HESSIAN2_REPLY",
            Boolean.TRUE);

    /**
     * enable hessian2 debug . The default value is false.
     */
    public static final ClientOption<Boolean> DEBUG = ClientOption.define("HESSIAN_DEBUG", Boolean.FALSE);

    private HessianClientOptions() {
    }

}
