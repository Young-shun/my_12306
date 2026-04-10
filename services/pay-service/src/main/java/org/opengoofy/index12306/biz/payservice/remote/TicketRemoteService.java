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

package org.opengoofy.index12306.biz.payservice.remote;

import org.opengoofy.index12306.biz.payservice.remote.dto.PayCallbackTicketReqDTO;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 车票远程服务调用
 */
@FeignClient(value = "index12306-ticket${unique-name:}-service", url = "${aggregation.remote-url:}")
public interface TicketRemoteService {

  /**
   * 支付成功后同步回写车票和座位状态
   */
  @PostMapping("/api/ticket-service/ticket/pay/callback")
  Result<Boolean> payCallbackTicket(@RequestBody PayCallbackTicketReqDTO requestParam);
}
