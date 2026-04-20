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

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.payservice.dao.entity.RefundTaskDO;
import org.opengoofy.index12306.biz.payservice.dao.mapper.RefundTaskMapper;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
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
    updateTaskStatus(refundTaskId, status, errorMessage, null);
  }

  /**
   * 独立提交退款任务状态和退款结果，避免被消息消费主事务回滚。
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
  public void updateTaskStatus(String refundTaskId, Integer status, String errorMessage, String refundResult) {
    RefundTaskDO taskDO = new RefundTaskDO();
    taskDO.setStatus(status);
    taskDO.setErrorMessage(errorMessage);
    taskDO.setRefundResult(refundResult);
    if ((status == 3 || status == 4) && errorMessage != null) {
      taskDO.setNextRetryTime(new Date(System.currentTimeMillis() + 5 * 60 * 1000));
    }
    LambdaUpdateWrapper<RefundTaskDO> wrapper = Wrappers.lambdaUpdate(RefundTaskDO.class)
        .eq(RefundTaskDO::getRefundTaskId, refundTaskId);
    refundTaskMapper.update(taskDO, wrapper);
  }

  /**
   * 标记下游回写完成；当车票和订单都完成后，任务状态更新为成功。
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
  public void markCallbackCompleted(String refundTaskId, String callbackType) {
    LambdaQueryWrapper<RefundTaskDO> queryWrapper = Wrappers.lambdaQuery(RefundTaskDO.class)
        .eq(RefundTaskDO::getRefundTaskId, refundTaskId)
        .last("FOR UPDATE");
    RefundTaskDO taskDO = refundTaskMapper.selectOne(queryWrapper);
    if (taskDO == null) {
      throw new ServiceException("退款任务不存在");
    }

    RefundTaskDO updateTask = new RefundTaskDO();
    boolean ticketDone;
    boolean orderDone;
    if ("TICKET".equalsIgnoreCase(callbackType)) {
      updateTask.setTicketCallbackStatus(1);
      ticketDone = true;
      orderDone = Integer.valueOf(1).equals(taskDO.getOrderCallbackStatus());
    } else if ("ORDER".equalsIgnoreCase(callbackType)) {
      updateTask.setOrderCallbackStatus(1);
      ticketDone = Integer.valueOf(1).equals(taskDO.getTicketCallbackStatus());
      orderDone = true;
    } else {
      throw new ServiceException("回写类型错误");
    }

    LambdaUpdateWrapper<RefundTaskDO> updateWrapper = Wrappers.lambdaUpdate(RefundTaskDO.class)
        .eq(RefundTaskDO::getRefundTaskId, refundTaskId);
    refundTaskMapper.update(updateTask, updateWrapper);

    boolean canFinish = Integer.valueOf(4).equals(taskDO.getStatus())
        || Integer.valueOf(2).equals(taskDO.getStatus());
    if (ticketDone && orderDone && canFinish) {
      RefundTaskDO finishTask = new RefundTaskDO();
      finishTask.setStatus(2);
      finishTask.setErrorMessage(null);
      refundTaskMapper.update(finishTask, updateWrapper);
    }
  }
}