package server.thrift;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.internal.common.thrift.ThriftServiceMetadata;
import com.linecorp.armeria.service.test.thrift.main.SayHelloService;

/**
 * additional test for camel name support
 */
public class ThriftServiceMetadataTests {

    @Test
    void testCamelName() {
        final ThriftServiceMetadata metadata = new ThriftServiceMetadata(SayHelloService.Iface.class);
        assertThat(metadata.function("say_hello")).isNotNull();
        assertThat(metadata.function("sayHello")).isNotNull();
    }
}
