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

package org.opengoofy.index12306.biz.payservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 退款任务状态响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundTaskStatusRespDTO {

  /**
   * 退款任务ID
   */
  private String refundTaskId;

  /**
   * 任务状态：0-待处理，1-处理中，2-成功，3-失败
   */
  private Integer status;

  /**
   * 状态描述
   */
  private String statusDesc;

  /**
   * 已重试次数
   */
  private Integer retryCount;

  /**
   * 最大重试次数
   */
  private Integer maxRetryCount;

  /**
   * 错误信息（仅失败时）
   */
  private String errorMessage;

  /**
   * 创建时间
   */
  private Date createdAt;

  /**
   * 更新时间
   */
  private Date updatedAt;
}
