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

package org.opengoofy.index12306.biz.ticketservice.remote.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 异步退款任务请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundTaskReqDTO {

  /**
   * 退款任务ID
   */
  private String refundTaskId;

  /**
   * 订单号
   */
  private String orderSn;

  /**
   * 支付单号
   */
  private String paySn;

  /**
   * 退款类型：0-部分退款，1-全额退款
   */
  private Integer refundType;

  /**
   * 退款总金额（单位：分）
   */
  private Integer refundAmount;

  /**
   * 退款详情列表
   */
  private List<RefundTaskDetailDTO> refundDetails;
}
