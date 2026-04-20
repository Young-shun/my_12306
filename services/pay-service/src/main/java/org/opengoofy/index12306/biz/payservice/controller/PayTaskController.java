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

import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.payservice.dto.PayTaskCallbackCompleteReqDTO;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.framework.starter.web.Results;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

import static org.opengoofy.index12306.biz.payservice.common.constant.RedisKeyConstant.PAY_CALLBACK_DONE;
import static org.opengoofy.index12306.biz.payservice.common.constant.RedisKeyConstant.PAY_CALLBACK_ORDER_DONE;
import static org.opengoofy.index12306.biz.payservice.common.constant.RedisKeyConstant.PAY_CALLBACK_TICKET_DONE;

/**
 * 支付回写任务控制器
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/pay-service/pay-task")
public class PayTaskController {

  private final DistributedCache distributedCache;

  /**
   * 下游回写完成回执。
   */
  @PostMapping("/callback/complete")
  public Result<Boolean> callbackComplete(@RequestBody PayTaskCallbackCompleteReqDTO requestParam) {
    if (requestParam.getOrderSn() == null || requestParam.getOrderSn().isBlank()) {
      throw new ServiceException("订单号不能为空");
    }
    String orderSn = requestParam.getOrderSn();
    if ("ORDER".equalsIgnoreCase(requestParam.getCallbackType())) {
      distributedCache.put(PAY_CALLBACK_ORDER_DONE + orderSn, Boolean.TRUE, 24, TimeUnit.HOURS);
    } else if ("TICKET".equalsIgnoreCase(requestParam.getCallbackType())) {
      distributedCache.put(PAY_CALLBACK_TICKET_DONE + orderSn, Boolean.TRUE, 24, TimeUnit.HOURS);
    } else {
      throw new ServiceException("回写类型错误");
    }

    boolean done = Boolean.TRUE.equals(distributedCache.hasKey(PAY_CALLBACK_ORDER_DONE + orderSn))
        && Boolean.TRUE.equals(distributedCache.hasKey(PAY_CALLBACK_TICKET_DONE + orderSn));
    if (done) {
      distributedCache.put(PAY_CALLBACK_DONE + orderSn, Boolean.TRUE, 24, TimeUnit.HOURS);
    }
    return Results.success(done);
  }

  /**
   * 查询支付回写最终完成态。
   */
  @GetMapping("/callback/status")
  public Result<Boolean> queryCallbackStatus(@RequestParam("orderSn") String orderSn) {
    return Results.success(Boolean.TRUE.equals(distributedCache.hasKey(PAY_CALLBACK_DONE + orderSn)));
  }
}
