/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opengoofy.index12306.biz.gatewayservice.filter;

import cn.hutool.core.util.StrUtil;
import com.alibaba.nacos.client.naming.utils.CollectionUtils;
import org.opengoofy.index12306.biz.gatewayservice.config.Config;
import org.opengoofy.index12306.biz.gatewayservice.toolkit.JWTUtil;
import org.opengoofy.index12306.biz.gatewayservice.toolkit.UserInfoDTO;
import org.opengoofy.index12306.framework.starter.bases.constant.UserConstant;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * SpringCloud Gateway Token 拦截器
 */
@Component
public class TokenValidateGatewayFilterFactory extends AbstractGatewayFilterFactory<Config> {

    private static final String USER_SERVICE_NAME = "index12306-user${unique-name:}-service";
    private static final String CHECK_LOGIN_PATH = "/api/user-service/check-login";

    private final WebClient.Builder webClientBuilder;
    private final Environment environment;

    public TokenValidateGatewayFilterFactory(WebClient.Builder webClientBuilder, Environment environment) {
        super(Config.class);
        this.webClientBuilder = webClientBuilder;
        this.environment = environment;
    }

    /**
     * 注销用户时需要传递 Token
     */
    public static final String DELETION_PATH = "/api/user-service/deletion";

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            String requestPath = request.getPath().toString();
            if (isPathInBlackPreList(requestPath, config.getBlackPathPre())) {
                String token = request.getHeaders().getFirst("Authorization");
                UserInfoDTO userInfo = JWTUtil.parseJwtToken(token);
                return validateToken(token, userInfo).flatMap(valid -> {
                    if (!valid) {
                        return unauthorized(exchange.getResponse());
                    }
                    ServerHttpRequest.Builder builder = exchange.getRequest().mutate().headers(httpHeaders -> {
                        httpHeaders.set(UserConstant.USER_ID_KEY, userInfo.getUserId());
                        httpHeaders.set(UserConstant.USER_NAME_KEY, userInfo.getUsername());
                        httpHeaders.set(UserConstant.REAL_NAME_KEY,
                                URLEncoder.encode(userInfo.getRealName(), StandardCharsets.UTF_8));
                        if (Objects.equals(requestPath, DELETION_PATH)) {
                            httpHeaders.set(UserConstant.USER_TOKEN_KEY, token);
                        }
                    });
                    return chain.filter(exchange.mutate().request(builder.build()).build());
                });
            }
            return chain.filter(exchange);
        };
    }

    private boolean isPathInBlackPreList(String requestPath, List<String> blackPathPre) {
        if (CollectionUtils.isEmpty(blackPathPre)) {
            return false;
        }
        return blackPathPre.stream().anyMatch(requestPath::startsWith);
    }

    private Mono<Boolean> validateToken(String token, UserInfoDTO userInfo) {
        if (userInfo == null || StrUtil.isBlank(token)) {
            return Mono.just(false);
        }
        String userServiceName = environment.resolvePlaceholders(USER_SERVICE_NAME);
        return webClientBuilder.build()
                .get()
                .uri("http://" + userServiceName + CHECK_LOGIN_PATH + "?accessToken={accessToken}", token)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Result<Object>>() {
                })
                .map(result -> result != null && result.isSuccess() && result.getData() != null)
                .onErrorReturn(false);
    }

    private Mono<Void> unauthorized(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return response.setComplete();
    }
}
