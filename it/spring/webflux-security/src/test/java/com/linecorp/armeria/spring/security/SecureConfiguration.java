/*
 * Copyright 2021 LINE Corporation
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
 * under the License
 */

package com.linecorp.armeria.spring.security;

import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.server.SecurityWebFilterChain;

@EnableWebFluxSecurity
public class SecureConfiguration {

    @Bean
    SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        return http.csrf().disable()
                   .authorizeExchange()
                   .pathMatchers("/api").hasAuthority("ROLE_USER")
                   .anyExchange().authenticated()
                   .and().formLogin()
                   .and().build();
    }

    @Bean
    ReactiveUserDetailsService userDetailsService() {
        final UserDetails userDetails = User.withDefaultPasswordEncoder()
                                            .username("user")
                                            .password("password")
                                            .roles("USER")
                                            .build();
        return new MapReactiveUserDetailsService(userDetails);
    }
}
