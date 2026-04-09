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

package org.opengoofy.index12306.biz.orderservice.dto.req;

import lombok.Data;

import java.util.List;

/**
 * 退款后同步回写订单请求参数
 */
@Data
public class RefundCallbackOrderUpdateReqDTO {

  /**
   * 订单号
   */
  private String orderSn;

  /**
   * 退款类型：0 部分退款 1 全部退款
   */
  private Integer refundType;

  /**
   * 部分退款的子订单记录 ID 集合
   */
  private List<String> orderItemRecordIds;
}
