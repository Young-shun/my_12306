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

package org.opengoofy.index12306.biz.payservice.controller;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.payservice.common.enums.PayChannelEnum;
import org.opengoofy.index12306.biz.payservice.convert.PayCallbackRequestConvert;
import org.opengoofy.index12306.biz.payservice.dto.PayCallbackCommand;
import org.opengoofy.index12306.biz.payservice.dto.base.PayCallbackRequest;
import org.opengoofy.index12306.biz.payservice.handler.AliPayCallbackHandler;
import org.opengoofy.index12306.framework.starter.designpattern.strategy.AbstractStrategyChoose;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 支付结果回调
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PayCallbackController {

    private final AbstractStrategyChoose abstractStrategyChoose;

    /**
     * 支付宝回调
     * 调用支付宝支付后，支付宝会调用此接口发送支付结果
     */
    @PostMapping("/api/pay-service/callback/alipay")
    public String callbackAlipay(@RequestParam Map<String, Object> requestParam) {
        try {
            PayCallbackCommand payCallbackCommand = new PayCallbackCommand();
            payCallbackCommand.setChannel(PayChannelEnum.ALI_PAY.getCode());
            payCallbackCommand.setOrderRequestId(String.valueOf(requestParam.get("out_trade_no")));
            payCallbackCommand.setTradeStatus(toStringValue(requestParam, "trade_status"));
            payCallbackCommand.setTradeNo(toStringValue(requestParam, "trade_no"));
            String gmtPayment = toStringValue(requestParam, "gmt_payment");
            if (StrUtil.isNotBlank(gmtPayment)) {
                payCallbackCommand.setGmtPayment(DateUtil.parse(gmtPayment));
            }
            String buyerPayAmount = toStringValue(requestParam, "buyer_pay_amount");
            if (StrUtil.isNotBlank(buyerPayAmount)) {
                payCallbackCommand.setBuyerPayAmount(new BigDecimal(buyerPayAmount));
            }
            PayCallbackRequest payCallbackRequest = PayCallbackRequestConvert
                    .command2PayCallbackRequest(payCallbackCommand);
            /**
             * {@link AliPayCallbackHandler}
             */
            // 策略模式：通过策略模式封装支付回调渠道，支付回调时动态选择对应的支付回调组件
            abstractStrategyChoose.chooseAndExecute(payCallbackRequest.buildMark(), payCallbackRequest);
            return "success";
        } catch (Exception ex) {
            log.error("支付宝回调处理失败，params={}", requestParam, ex);
            return "failure";
        }
    }

    private static String toStringValue(Map<String, Object> requestParam, String key) {
        Object value = requestParam.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
