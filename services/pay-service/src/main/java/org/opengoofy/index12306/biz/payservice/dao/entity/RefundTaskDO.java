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

package org.opengoofy.index12306.biz.payservice.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.opengoofy.index12306.framework.starter.database.base.BaseDO;

import java.util.Date;

/**
 * 退款任务实体（异步化设计）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_refund_task")
public class RefundTaskDO extends BaseDO {

  /**
   * id
   */
  private Long id;

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
   * 退款类型 0-部分退款 1-全部退款
   */
  private Integer refundType;

  /**
   * 退款金额(分)
   */
  private Integer refundAmount;

  /**
   * 退款明细(JSON格式,包含乘客信息)
   */
  private String refundDetail;

  /**
   * 任务状态 0-待处理 1-处理中 2-成功 3-失败
   */
  private Integer status;

  /**
   * 失败原因
   */
  private String errorMessage;

  /**
   * 退款结果(第三方返回)
   */
  private String refundResult;

  /**
   * 重试次数
   */
  private Integer retryCount;

  /**
   * 最大重试次数
   */
  private Integer maxRetryCount;

  /**
   * 下次重试时间
   */
  private Date nextRetryTime;
}
