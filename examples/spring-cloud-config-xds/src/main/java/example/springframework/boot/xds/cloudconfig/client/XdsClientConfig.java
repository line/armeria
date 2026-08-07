package example.springframework.boot.xds.cloudconfig.client;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;

@Configuration
class XdsClientConfig {

    @Bean
    XdsHttpPreprocessor xdsHttpPreprocessor(XdsBootstrap xdsBootstrap) {
        return XdsHttpPreprocessor.ofListener("test-listener", xdsBootstrap);
    }

    @Bean
    WebClient xdsWebClient(XdsHttpPreprocessor xdsHttpPreprocessor) {
        return WebClient.of(xdsHttpPreprocessor);
    }
}
