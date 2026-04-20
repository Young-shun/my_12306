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

package org.opengoofy.index12306.biz.payservice.mq.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.opengoofy.index12306.biz.payservice.dto.RefundTaskDetailDTO;

import java.io.Serializable;
import java.util.List;

/**
 * 退款任务事件（异步处理）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundTaskEvent implements Serializable {

  private static final long serialVersionUID = 1L;

  /**
   * 退款任务唯一标识
   */
  private String refundTaskId;

  /**
   * 订单号
   */
  private String orderSn;

  /**
   * 支付流水号
   */
  private String paySn;

  /**
   * 退款类型 0-部分 1-全部
   */
  private Integer refundType;

  /**
   * 退款金额
   */
  private Integer refundAmount;

  /**
   * 退款明细
   */
  private List<RefundTaskDetailDTO> refundDetails;
}
