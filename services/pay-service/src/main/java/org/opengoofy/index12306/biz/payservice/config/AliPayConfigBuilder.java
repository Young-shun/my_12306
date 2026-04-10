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

package org.opengoofy.index12306.biz.payservice.config;

import cn.hutool.core.util.StrUtil;
import com.alipay.api.AlipayConfig;

/**
 * Build AlipayConfig with normalized fields to avoid signature mismatch.
 */
public final class AliPayConfigBuilder {

  private AliPayConfigBuilder() {
  }

  public static AlipayConfig build(AliPayProperties properties) {
    AlipayConfig config = new AlipayConfig();
    config.setServerUrl(StrUtil.trim(properties.getServerUrl()));
    config.setAppId(StrUtil.trim(properties.getAppId()));
    config.setPrivateKey(normalizeKey(properties.getPrivateKey()));
    config.setFormat(defaultIfBlank(properties.getFormat(), "json"));
    config.setCharset(normalizeCharset(properties.getCharset()));
    config.setSignType(defaultIfBlank(properties.getSignType(), "RSA2"));
    config.setAlipayPublicKey(normalizeKey(properties.getAlipayPublicKey()));
    return config;
  }

  private static String normalizeKey(String key) {
    if (StrUtil.isBlank(key)) {
      return key;
    }
    return StrUtil.replace(
        StrUtil.replace(
            StrUtil.replace(
                StrUtil.replace(
                    StrUtil.replace(StrUtil.trim(key), "-----BEGIN PRIVATE KEY-----", ""),
                    "-----BEGIN RSA PRIVATE KEY-----",
                    ""),
                "-----END PRIVATE KEY-----",
                ""),
            "-----BEGIN PUBLIC KEY-----",
            ""),
        "-----END PUBLIC KEY-----",
        "")
        .replace("-----END RSA PRIVATE KEY-----", "")
        .replaceAll("\\s+", "");
  }

  private static String normalizeCharset(String charset) {
    if (StrUtil.isBlank(charset)) {
      return "UTF-8";
    }
    String normalized = StrUtil.trim(charset);
    if (StrUtil.equalsAnyIgnoreCase(normalized, "UTF8", "UTF_8")) {
      return "UTF-8";
    }
    return normalized;
  }

  private static String defaultIfBlank(String value, String defaultValue) {
    return StrUtil.isBlank(value) ? defaultValue : StrUtil.trim(value);
  }
}
