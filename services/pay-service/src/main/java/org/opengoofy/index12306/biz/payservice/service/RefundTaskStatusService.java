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

package org.opengoofy.index12306.biz.payservice.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.payservice.dao.entity.RefundTaskDO;
import org.opengoofy.index12306.biz.payservice.dao.mapper.RefundTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/**
 * 退款任务状态服务
 */
@Service
@RequiredArgsConstructor
public class RefundTaskStatusService {

  private final RefundTaskMapper refundTaskMapper;

  /**
   * 独立提交退款任务状态，避免被消息消费主事务回滚。
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
  public void updateTaskStatus(String refundTaskId, Integer status, String errorMessage) {
    RefundTaskDO taskDO = new RefundTaskDO();
    taskDO.setStatus(status);
    taskDO.setErrorMessage(errorMessage);
    if (status == 3 && errorMessage != null) {
      taskDO.setNextRetryTime(new Date(System.currentTimeMillis() + 5 * 60 * 1000));
    }
    LambdaUpdateWrapper<RefundTaskDO> wrapper = Wrappers.lambdaUpdate(RefundTaskDO.class)
        .eq(RefundTaskDO::getRefundTaskId, refundTaskId);
    refundTaskMapper.update(taskDO, wrapper);
  }
}