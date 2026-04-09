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

import java.util.Date;
import java.util.List;

/**
 * 购票时间区间冲突检查请求参数
 */
@Data
public class PurchaseTicketConflictCheckReqDTO {

  /**
   * 乘车人证件号集合
   */
  private List<String> idCardList;

  /**
   * 当前拟购买车票的出发时间
   */
  private Date departureTime;

  /**
   * 当前拟购买车票的到达时间
   */
  private Date arrivalTime;

  /**
   * 当前拟购买车票的乘车日期
   */
  private Date ridingDate;
}
