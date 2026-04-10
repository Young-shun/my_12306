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
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * Validate Alipay key configuration on startup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AliPayConfigVerifier {

  private final AliPayProperties aliPayProperties;

  @PostConstruct
  public void verify() {
    try {
      String privateKey = normalizeKey(aliPayProperties.getPrivateKey());
      String alipayPublicKey = normalizeKey(aliPayProperties.getAlipayPublicKey());
      String appPublicKey = normalizeKey(aliPayProperties.getAppPublicKey());
      String appPublicFromPrivate = deriveAppPublicKey(privateKey);

      log.info(
          "Alipay key check appId={} private.fp={} alipayPublic.fp={} appPublicFromPrivate.fp={} appPublicInConfig.fp={}",
          StrUtil.trim(aliPayProperties.getAppId()),
          shortFingerprint(privateKey),
          shortFingerprint(alipayPublicKey),
          shortFingerprint(appPublicFromPrivate),
          shortFingerprint(appPublicKey));

      // For sandbox troubleshooting, this is public material and can be pasted to
      // Alipay app public key setting.
      log.info("Alipay derived app public key from private-key: {}", appPublicFromPrivate);

      if (StrUtil.isNotBlank(appPublicKey) && !StrUtil.equals(appPublicFromPrivate, appPublicKey)) {
        log.error("Configured app-public-key does not match private-key derived public key. " +
            "Please keep app-public-key and private-key as the same key pair.");
      }

      if (StrUtil.equals(appPublicFromPrivate, alipayPublicKey)) {
        log.error("alipay-public-key equals app public key derived from private-key. " +
            "Please replace alipay-public-key with Alipay platform public key from sandbox.");
      }
    } catch (Exception ex) {
      log.error("Alipay key check failed", ex);
    }
  }

  private static String deriveAppPublicKey(String privateKeyBase64) throws Exception {
    byte[] privateBytes = Base64.getDecoder().decode(privateKeyBase64);
    KeyFactory keyFactory = KeyFactory.getInstance("RSA");
    PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
    RSAPrivateCrtKey crtKey = (RSAPrivateCrtKey) privateKey;
    RSAPublicKeySpec publicKeySpec = new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent());
    byte[] publicBytes = keyFactory.generatePublic(publicKeySpec).getEncoded();
    return Base64.getEncoder().encodeToString(publicBytes);
  }

  private static String normalizeKey(String key) {
    if (StrUtil.isBlank(key)) {
      return key;
    }
    return StrUtil.replace(
        StrUtil.replace(
            StrUtil.replace(
                StrUtil.replace(StrUtil.trim(key), "-----BEGIN PRIVATE KEY-----", ""),
                "-----END PRIVATE KEY-----",
                ""),
            "-----BEGIN PUBLIC KEY-----",
            ""),
        "-----END PUBLIC KEY-----",
        "")
        .replaceAll("\\s+", "");
  }

  private static String shortFingerprint(String value) {
    if (StrUtil.isBlank(value)) {
      return "empty";
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      String fp = Base64.getEncoder().encodeToString(hash);
      return fp.substring(0, Math.min(16, fp.length()));
    } catch (Exception ex) {
      return "n/a";
    }
  }
}
