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

package com.linecorp.armeria.spring.mixed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.BeansException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.junit4.SpringRunner;

import com.linecorp.armeria.spring.ArmeriaAutoConfiguration;
import com.linecorp.armeria.spring.web.reactive.ArmeriaReactiveWebServerFactory;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class SpringApplicationItTest {
    @Inject
    private ApplicationContext applicationContext;
    @Inject
    private TestRestTemplate restTemplate;
    @Inject
    private GreetingController greetingController;

    @Test
    public void contextLoads() {
        assertThat(greetingController).isNotNull();
        assertThat(applicationContext.getBean(ArmeriaReactiveWebServerFactory.class)).isNotNull();
        assertThatThrownBy(() -> {
            applicationContext.getBean(ArmeriaAutoConfiguration.class);
        }).isInstanceOf(BeansException.class);
    }

    @Test
    public void greetingShouldReturnDefaultMessage() throws Exception {
        assertThat(restTemplate.getForObject("/greeting", String.class))
                .contains("Hello, World!");
    }

    @Test
    public void greetingShouldReturnUsersMessage() throws Exception {
        assertThat(restTemplate.getForObject("/greeting?name=Armeria", String.class))
                .contains("Hello, Armeria!");
    }

    @Test
    public void greetingShouldReturn404() throws Exception {
        assertThat(restTemplate.getForEntity("/greet", Void.class).getStatusCode())
                .isEqualByComparingTo(HttpStatus.NOT_FOUND);
    }
}
