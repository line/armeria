
package cn.swiftpass.feynman.hessian.server;

import cn.swiftpass.feynman.hessian.internal.HessianServiceImplMetadata;
import cn.swiftpass.feynman.hessian.internal.server.HessianCallService;
import cn.swiftpass.feynman.hessian.internal.server.HessianHttpServiceImpl;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.server.RpcService;
import com.linecorp.armeria.server.ServiceRequestContext;
import lombok.Value;

import javax.annotation.Nullable;
import java.beans.Introspector;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * {@link HessianHttpService} 的 builder. 允许绑定多个 Hessian服务
 *
 * <h2>Example</h2> <pre>{@code
 * Server server =
 *     Server.builder()
 *           .http(8080)
 *           .service("/", HessianHttpService.builder()
 *                                     .addService(new FooServiceImpl())              //
 *                                     .addService(BarService.class, new BarServiceImpl())
 *                                     .addService("foobar", new FooBarServiceImpl()) //
 *                                     .build())
 *           .build();
 * }</pre>
 *
 * <p>
 * </p>
 */
public final class HessianHttpServiceBuilder {

    static final SerializationFormat HESSIAN = SerializationFormat.of("hessian");

    private static final BiFunction<? super ServiceRequestContext, ? super Throwable, ? extends RpcResponse> defaultExceptionHandler = (
            ctx, cause) -> RpcResponse.ofFailure(cause);

    private static final Function<Object, Class<?>> defaultApiClassDetector = o -> {
        Class<?>[] interfaces = o.getClass().getInterfaces();
        if (interfaces.length != 1) {
            throw new IllegalArgumentException(
                    "Expect hessian implementation " + o.getClass() + " to implement exactly one interface");
        }
        return interfaces[0];
    };

    private final ImmutableList.Builder<ServiceEntry> hessianService = ImmutableList.builder();

    private SerializationFormat defaultSerializationFormat = HESSIAN;

    private Function<Object, Class<?>> apiClassDetector;

    private Function<Class<?>, String> autoServicePathProducer = apiClass -> Introspector
            .decapitalize(apiClass.getSimpleName());

    private String prefix = "";

    private String suffix = "";

    @Nullable
    private Function<? super RpcService, ? extends RpcService> decoratorFunction;

    private BiFunction<? super ServiceRequestContext, ? super Throwable, ? extends RpcResponse> exceptionHandler = defaultExceptionHandler;

    HessianHttpServiceBuilder() {
        this.apiClassDetector = defaultApiClassDetector;
    }

    /**
     * 服务的路由前缀。必须使用'/'开头。 如果要用‘/'分隔，需要显示添加。比如’/services/'
     */
    public HessianHttpServiceBuilder prefix(String prefix) {
        requireNonNull(prefix, "prefix");
        checkArgument(prefix.startsWith("/"), "prefix must start with '/', got +", prefix);
        this.prefix = prefix;
        return this;
    }

    /**
     * 服务的路由后缀缀。比如'.hs'
     */
    public HessianHttpServiceBuilder suffix(String suffix) {
        this.suffix = suffix;
        return this;
    }

    /**
     * 对对象来获取hessian的apiClass
     */
    public HessianHttpServiceBuilder apiClassDetector(Function<Object, Class<?>> apiClassDetector) {
        this.apiClassDetector = apiClassDetector;
        return this;
    }

    /**
     * 增加服务。
     * @param path 路径。
     * @param apiClass hessian的api
     * @param implementation hessian的实现
     * @param blocking 是否非阻塞的实现
     */
    public HessianHttpServiceBuilder addService(String path, Class<?> apiClass, Object implementation,
            boolean blocking) {
        hessianService.add(new ServiceEntry(path, apiClass, implementation, blocking));
        return this;
    }

    /**
     * 增加服务。
     * @param path 路径。
     * @param apiClass hessian的api
     * @param implementation hessian的实现
     */
    public HessianHttpServiceBuilder addService(String path, Class<?> apiClass, Object implementation) {
        hessianService.add(new ServiceEntry(path, apiClass, implementation, true));
        return this;
    }

    /**
     * 增加服务。
     * @param path 路径。
     * @param implementation hessian的实现
     */
    public HessianHttpServiceBuilder addService(String path, Object implementation) {
        hessianService.add(new ServiceEntry(path, null, implementation, true));
        return this;
    }

    /**
     * 增加服务， 使用 {@link #autoServicePathProducer} 来确定path.
     * @param apiClass hessian的api
     * @param implementation hessian的实现
     */
    public HessianHttpServiceBuilder addService(Class<?> apiClass, Object implementation) {
        hessianService.add(new ServiceEntry(null, apiClass, implementation, true));
        return this;
    }

    /**
     * 增加服务， 使用 {@link #autoServicePathProducer} 来确定path. 增加服务. 使用
     * {@link #apiClassDetector} 来确定api 使用 {@link #autoServicePathProducer} 来确定path.
     * @param implementation hessian的实现
     */
    public HessianHttpServiceBuilder addService(Object implementation) {
        hessianService.add(new ServiceEntry(null, null, implementation, true));
        return this;
    }

    /**
     * Sets the {@link BiFunction} that returns an {@link RpcResponse} using the given
     * {@link Throwable} and {@link ServiceRequestContext}.
     */
    public HessianHttpServiceBuilder exceptionHandler(
            BiFunction<? super ServiceRequestContext, ? super Throwable, ? extends RpcResponse> exceptionHandler) {
        this.exceptionHandler = requireNonNull(exceptionHandler, "exceptionHandler");
        return this;
    }

    /**
     * A {@code Function<? super RpcService, ? extends RpcService>} to decorate the
     * {@link RpcService}.
     */
    public HessianHttpServiceBuilder decorate(Function<? super RpcService, ? extends RpcService> decoratorFunction) {
        requireNonNull(decoratorFunction, "decoratorFunction");
        if (this.decoratorFunction == null) {
            this.decoratorFunction = decoratorFunction;
        }
        else {
            this.decoratorFunction = this.decoratorFunction.andThen(decoratorFunction);
        }
        return this;
    }

    private RpcService decorate(RpcService service) {
        if (decoratorFunction != null) {
            return service.decorate(decoratorFunction);
        }
        return service;
    }

    /**
     * Builds a new instance of {@link HessianHttpServiceImpl}.
     */
    public HessianHttpServiceImpl build() {
        @SuppressWarnings("UnstableApiUsage")
        ImmutableList<ServiceEntry> implementations = hessianService.build();
        ImmutableMap.Builder<String, HessianServiceImplMetadata> path2ServiceMapBuilder = ImmutableMap.builder();
        for (ServiceEntry entry : implementations) {
            Class<?> apiClass = entry.apiClass != null ? entry.apiClass : apiClassDetector.apply(entry.implementation);
            checkArgument(apiClass != null, "apiClass must not be null");
            String path = entry.path != null ? entry.path : autoServicePathProducer.apply(apiClass);
            checkArgument(path != null, "path must not be null");
            String fullPath = buildPath(path, prefix, suffix);
            HessianServiceImplMetadata ssm = new HessianServiceImplMetadata(apiClass, entry.implementation,
                    entry.blocking);
            path2ServiceMapBuilder.put(fullPath, ssm);
        }
        final HessianCallService hcs = HessianCallService.of(path2ServiceMapBuilder.build());
        return build0(hcs, path2ServiceMapBuilder.build());
    }

    private static String buildPath(String path, String prefix, String suffix) {
        String fullPath;
        if (Strings.isNullOrEmpty(prefix) || path.startsWith(prefix)) {
            fullPath = path;
        }
        else {
            fullPath = prefix + path;
            fullPath = fullPath.replaceAll("//", "/");
        }

        if (!Strings.isNullOrEmpty(suffix) && !path.endsWith(suffix)) {
            fullPath = fullPath + suffix;
        }
        if (fullPath.startsWith("/")) {
            return fullPath;
        }
        else {
            return '/' + fullPath;
        }
    }

    private HessianHttpServiceImpl build0(RpcService tcs, Map<String, HessianServiceImplMetadata> path2ServiceMap) {

        return new HessianHttpServiceImpl(decorate(tcs), defaultSerializationFormat, exceptionHandler);
    }

    @Value
    private static class ServiceEntry {

        @Nullable
        String path;

        @Nullable
        Class<?> apiClass;

        Object implementation;

        boolean blocking;

    }

}
