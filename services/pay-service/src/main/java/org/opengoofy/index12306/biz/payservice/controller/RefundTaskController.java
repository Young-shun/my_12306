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

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.opengoofy.index12306.biz.payservice.dao.entity.RefundTaskDO;
import org.opengoofy.index12306.biz.payservice.dao.mapper.RefundTaskMapper;
import org.opengoofy.index12306.biz.payservice.dto.RefundTaskCallbackCompleteReqDTO;
import org.opengoofy.index12306.biz.payservice.dto.RefundTaskReqDTO;
import org.opengoofy.index12306.biz.payservice.dto.RefundTaskStatusRespDTO;
import org.opengoofy.index12306.biz.payservice.mq.event.RefundTaskEvent;
import org.opengoofy.index12306.biz.payservice.mq.produce.RefundTaskSendProducer;
import org.opengoofy.index12306.biz.payservice.service.RefundTaskStatusService;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.framework.starter.web.Results;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * 异步退款任务接收端点
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/pay-service/refund-task")
public class RefundTaskController {

  private final RefundTaskSendProducer refundTaskSendProducer;
  private final RefundTaskMapper refundTaskMapper;
  private final RefundTaskStatusService refundTaskStatusService;

  /**
   * 提交退款任务
   * ticket-service 调用此端点，异步提交退款处理，立即返回
   */
  @PostMapping("/submit")
  public Result<Void> submitRefundTask(@RequestBody RefundTaskReqDTO requestParam) {
    log.info("收到异步退款任务提交请求，orderSn: {}, refundTaskId: {}, amount: {}",
        requestParam.getOrderSn(), requestParam.getRefundTaskId(), requestParam.getRefundAmount());

    try {
      RefundTaskDO refundTaskDO = RefundTaskDO.builder()
          .refundTaskId(requestParam.getRefundTaskId())
          .orderSn(requestParam.getOrderSn())
          .paySn(requestParam.getPaySn())
          .refundType(requestParam.getRefundType())
          .refundAmount(requestParam.getRefundAmount())
          .refundDetail(JSON.toJSONString(requestParam.getRefundDetails()))
          .orderItemRecordIds(JSON.toJSONString(requestParam.getOrderItemRecordIds()))
          .status(0)
          .ticketCallbackStatus(0)
          .orderCallbackStatus(0)
          .retryCount(0)
          .maxRetryCount(3)
          .build();
      int insertResult = refundTaskMapper.insert(refundTaskDO);
      if (insertResult <= 0) {
        throw new ServiceException("创建退款任务失败");
      }

      // 转换请求 DTO 为 MQ 事件
      RefundTaskEvent refundTaskEvent = RefundTaskEvent.builder()
          .refundTaskId(requestParam.getRefundTaskId())
          .orderSn(requestParam.getOrderSn())
          .paySn(requestParam.getPaySn())
          .refundType(requestParam.getRefundType())
          .refundAmount(requestParam.getRefundAmount())
          .refundDetails(requestParam.getRefundDetails())
          .orderItemRecordIds(requestParam.getOrderItemRecordIds())
          .build();

      // 发送消息到 MQ（同步发送确保消息可靠性）
      refundTaskSendProducer.sendMessage(refundTaskEvent);

      log.info("异步退款任务已提交到 MQ，refundTaskId: {}, orderSn: {}",
          requestParam.getRefundTaskId(), requestParam.getOrderSn());

      return Results.success();
    } catch (Exception e) {
      log.error("提交异步退款任务失败，orderSn: {}", requestParam.getOrderSn(), e);
      RefundTaskDO updateTask = new RefundTaskDO();
      updateTask.setStatus(3);
      updateTask.setErrorMessage(e.getMessage());
      updateTask.setNextRetryTime(new java.util.Date(System.currentTimeMillis() + 5 * 60 * 1000));
      LambdaQueryWrapper<RefundTaskDO> updateWrapper = Wrappers.lambdaQuery(RefundTaskDO.class)
          .eq(RefundTaskDO::getRefundTaskId, requestParam.getRefundTaskId());
      refundTaskMapper.update(updateTask, updateWrapper);
      throw new ServiceException("提交退款任务失败");
    }
  }

  /**
   * 查询退款任务状态
   */
  @GetMapping("/query/{refundTaskId}")
  public Result<RefundTaskStatusRespDTO> queryRefundTaskStatus(@PathVariable("refundTaskId") String refundTaskId) {
    LambdaQueryWrapper<RefundTaskDO> queryWrapper = Wrappers.lambdaQuery(RefundTaskDO.class)
        .eq(RefundTaskDO::getRefundTaskId, refundTaskId);
    RefundTaskDO refundTask = refundTaskMapper.selectOne(queryWrapper);

    if (Objects.isNull(refundTask)) {
      throw new ServiceException("退款任务不存在");
    }

    RefundTaskStatusRespDTO respDTO = RefundTaskStatusRespDTO.builder().refundTaskId(refundTask.getRefundTaskId())
        .status(refundTask.getStatus()).statusDesc(getStatusDesc(refundTask.getStatus()))
        .retryCount(refundTask.getRetryCount()).maxRetryCount(refundTask.getMaxRetryCount())
        .errorMessage(refundTask.getErrorMessage()).createdAt(refundTask.getCreateTime())
        .updatedAt(refundTask.getUpdateTime()).build();

    return Results.success(respDTO);
  }

  /**
   * 下游回写完成回执
   */
  @PostMapping("/callback/complete")
  public Result<Boolean> callbackComplete(@RequestBody RefundTaskCallbackCompleteReqDTO requestParam) {
    refundTaskStatusService.markCallbackCompleted(requestParam.getRefundTaskId(), requestParam.getCallbackType());
    return Results.success(Boolean.TRUE);
  }

  /**
   * 获取状态描述
   */
  private String getStatusDesc(Integer status) {
    if (status == null) {
      return "未知";
    }
    switch (status) {
      case 0:
        return "待处理";
      case 1:
        return "处理中";
      case 2:
        return "回写完成";
      case 3:
        return "失败";
      case 4:
        return "退款成功待下游完成";
      default:
        return "未知";
    }
  }
}
