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

package org.opengoofy.index12306.biz.payservice.mq.consumer;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.opengoofy.index12306.biz.payservice.common.constant.PayRocketMQConstant;
import org.opengoofy.index12306.biz.payservice.common.enums.RefundTypeEnum;
import org.opengoofy.index12306.biz.payservice.common.enums.TradeStatusEnum;
import org.opengoofy.index12306.biz.payservice.convert.RefundRequestConvert;
import org.opengoofy.index12306.biz.payservice.dao.entity.PayDO;
import org.opengoofy.index12306.biz.payservice.dao.entity.RefundDO;
import org.opengoofy.index12306.biz.payservice.dao.entity.RefundTaskDO;
import org.opengoofy.index12306.biz.payservice.dao.mapper.PayMapper;
import org.opengoofy.index12306.biz.payservice.dao.mapper.RefundMapper;
import org.opengoofy.index12306.biz.payservice.dao.mapper.RefundTaskMapper;
import org.opengoofy.index12306.biz.payservice.dto.RefundCommand;
import org.opengoofy.index12306.biz.payservice.dto.RefundTaskDetailDTO;
import org.opengoofy.index12306.biz.payservice.mq.domain.MessageWrapper;
import org.opengoofy.index12306.biz.payservice.mq.event.RefundTaskEvent;
import org.opengoofy.index12306.biz.payservice.mq.event.RefundResultCallbackOrderEvent;
import org.opengoofy.index12306.biz.payservice.mq.produce.RefundResultCallbackOrderSendProduce;
import org.opengoofy.index12306.biz.payservice.dto.base.RefundRequest;
import org.opengoofy.index12306.biz.payservice.dto.base.RefundResponse;
import org.opengoofy.index12306.biz.payservice.remote.TicketOrderRemoteService;
import org.opengoofy.index12306.biz.payservice.remote.dto.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.payservice.service.RefundTaskStatusService;
import org.opengoofy.index12306.framework.starter.common.toolkit.BeanUtil;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.framework.starter.designpattern.strategy.AbstractStrategyChoose;
import org.opengoofy.index12306.framework.starter.idempotent.annotation.Idempotent;
import org.opengoofy.index12306.framework.starter.idempotent.enums.IdempotentSceneEnum;
import org.opengoofy.index12306.framework.starter.idempotent.enums.IdempotentTypeEnum;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Objects;

/**
 * 退款任务消费者（异步处理）
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = PayRocketMQConstant.REFUND_TASK_TOPIC_KEY, selectorExpression = PayRocketMQConstant.REFUND_TASK_TAG_KEY, consumerGroup = PayRocketMQConstant.REFUND_TASK_CONSUMER_GROUP_KEY)
public class RefundTaskConsumer implements RocketMQListener<MessageWrapper<RefundTaskEvent>> {

  private final PayMapper payMapper;
  private final RefundMapper refundMapper;
  private final RefundTaskMapper refundTaskMapper;
  private final TicketOrderRemoteService ticketOrderRemoteService;
  private final AbstractStrategyChoose abstractStrategyChoose;
  private final RefundResultCallbackOrderSendProduce refundResultCallbackOrderSendProduce;
  private final RefundTaskStatusService refundTaskStatusService;

  @Idempotent(uniqueKeyPrefix = "index12306-pay:refund_task:", key = "#message.getKeys()", type = IdempotentTypeEnum.SPEL, scene = IdempotentSceneEnum.MQ, keyTimeout = 7200L)
  @Transactional(rollbackFor = Exception.class)
  @Override
  public void onMessage(MessageWrapper<RefundTaskEvent> message) {
    RefundTaskEvent refundTaskEvent = message.getMessage();
    String refundTaskId = refundTaskEvent.getRefundTaskId();
    String orderSn = refundTaskEvent.getOrderSn();

    log.info("开始处理退款任务，refundTaskId: {}, orderSn: {}", refundTaskId, orderSn);

    try {
      // 1. 更新任务状态为处理中
      refundTaskStatusService.updateTaskStatus(refundTaskId, 1, null);

      // 2. 查询支付单
      LambdaQueryWrapper<PayDO> queryWrapper = Wrappers.lambdaQuery(PayDO.class)
          .eq(PayDO::getOrderSn, orderSn);
      PayDO payDO = payMapper.selectOne(queryWrapper);
      if (Objects.isNull(payDO)) {
        refundTaskStatusService.updateTaskStatus(refundTaskId, 3, "支付单不存在");
        throw new ServiceException("支付单不存在");
      }

      // 3. 创建退款单（逐乘客）
      createRefundRecords(refundTaskEvent, orderSn);

      // 4. 调用支付宝进行退款
      RefundCommand refundCommand = BeanUtil.convert(payDO, RefundCommand.class);
      refundCommand.setRefundTaskId(refundTaskId);
      refundCommand.setPayAmount(new BigDecimal(refundTaskEvent.getRefundAmount()));
      RefundRequest refundRequest = RefundRequestConvert.command2RefundRequest(refundCommand);
      RefundResponse result = abstractStrategyChoose.chooseAndExecuteResp(refundRequest.buildMark(), refundRequest);

      // 5. 更新支付单和退款单状态
      payDO.setStatus(result.getStatus());
      payDO.setPayAmount(payDO.getTotalAmount() - refundTaskEvent.getRefundAmount());
      LambdaUpdateWrapper<PayDO> payUpdateWrapper = Wrappers.lambdaUpdate(PayDO.class)
          .eq(PayDO::getOrderSn, orderSn);
      int payUpdateResult = payMapper.update(payDO, payUpdateWrapper);
      if (payUpdateResult <= 0) {
        refundTaskStatusService.updateTaskStatus(refundTaskId, 3, "修改支付单失败");
        throw new ServiceException("修改支付单退款结果失败");
      }

      // 更新退款单
      LambdaUpdateWrapper<RefundDO> refundUpdateWrapper = Wrappers.lambdaUpdate(RefundDO.class)
          .eq(RefundDO::getOrderSn, orderSn);
      RefundDO refundDO = new RefundDO();
      refundDO.setTradeNo(result.getTradeNo());
      refundDO.setStatus(result.getStatus());
      int refundUpdateResult = refundMapper.update(refundDO, refundUpdateWrapper);
      if (refundUpdateResult <= 0) {
        refundTaskStatusService.updateTaskStatus(refundTaskId, 3, "修改退款单失败");
        throw new ServiceException("修改退款单退款结果失败");
      }

      // 6. 更新任务状态为成功
      refundTaskStatusService.updateTaskStatus(refundTaskId, 2, null);

      // 7. 发送 MQ 通知订单和票务服务（仅成功时）
      if (Objects.equals(result.getStatus(), TradeStatusEnum.TRADE_CLOSED.tradeCode())) {
        RefundTypeEnum refundTypeEnum = Objects.equals(refundTaskEvent.getRefundType(), 0)
            ? RefundTypeEnum.PARTIAL_REFUND
            : RefundTypeEnum.FULL_REFUND;
        RefundResultCallbackOrderEvent callbackEvent = RefundResultCallbackOrderEvent.builder()
            .orderSn(orderSn)
            .refundTypeEnum(refundTypeEnum)
            .partialRefundTicketDetailList(
                BeanUtil.convert(refundTaskEvent.getRefundDetails(),
                    org.opengoofy.index12306.biz.payservice.remote.dto.TicketOrderPassengerDetailRespDTO.class))
            .build();
        refundResultCallbackOrderSendProduce.sendMessage(callbackEvent);
        log.info("退款任务成功，已发送 MQ 回调，refundTaskId: {}, orderSn: {}", refundTaskId, orderSn);
      }

    } catch (Exception e) {
      log.error("处理退款任务异常，refundTaskId: {}, orderSn: {}", refundTaskId, orderSn, e);
      refundTaskStatusService.updateTaskStatus(refundTaskId, 3, e.getMessage());
      // MQ 异常处理：如果是第一次失败，不需要重新投递，由定时任务负责重试
      throw new ServiceException("退款任务处理失败");
    }
  }

  /**
   * 创建退款记录
   */
  private void createRefundRecords(RefundTaskEvent refundTaskEvent, String orderSn) {
    // 查询订单详情
    Result<TicketOrderDetailRespDTO> orderDetailResult = ticketOrderRemoteService
        .queryTicketOrderByOrderSn(orderSn);
    if (!orderDetailResult.isSuccess() || Objects.isNull(orderDetailResult.getData())) {
      throw new ServiceException("车票订单不存在");
    }
    TicketOrderDetailRespDTO orderDetailRespDTO = orderDetailResult.getData();

    // 逐乘客创建退款单
    refundTaskEvent.getRefundDetails().forEach(detail -> {
      RefundDO refundDO = new RefundDO();
      refundDO.setOrderSn(orderSn);
      refundDO.setPaySn(refundTaskEvent.getPaySn());
      refundDO.setTrainId(orderDetailRespDTO.getTrainId());
      refundDO.setTrainNumber(orderDetailRespDTO.getTrainNumber());
      refundDO.setDeparture(orderDetailRespDTO.getDeparture());
      refundDO.setArrival(orderDetailRespDTO.getArrival());
      refundDO.setDepartureTime(orderDetailRespDTO.getDepartureTime());
      refundDO.setArrivalTime(orderDetailRespDTO.getArrivalTime());
      refundDO.setRidingDate(orderDetailRespDTO.getRidingDate());
      refundDO.setSeatType(detail.getSeatType());
      refundDO.setIdType(detail.getIdType());
      refundDO.setIdCard(detail.getIdCard());
      refundDO.setRealName(detail.getRealName());
      refundDO.setRefundTime(new Date());
      refundDO.setAmount(detail.getAmount());
      refundDO.setUserId(detail.getUserId());
      refundDO.setUsername(detail.getUsername());
      refundMapper.insert(refundDO);
    });
  }
}
