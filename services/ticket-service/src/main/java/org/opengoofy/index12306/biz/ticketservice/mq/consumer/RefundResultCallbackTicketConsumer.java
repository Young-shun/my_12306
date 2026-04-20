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

package org.opengoofy.index12306.biz.ticketservice.mq.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.opengoofy.index12306.biz.ticketservice.common.constant.TicketRocketMQConstant;
import org.opengoofy.index12306.biz.ticketservice.mq.domain.MessageWrapper;
import org.opengoofy.index12306.biz.ticketservice.mq.event.RefundResultCallbackTicketEvent;
import org.opengoofy.index12306.biz.ticketservice.remote.PayRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.RefundTaskCallbackCompleteReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.RefundCallbackTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.service.TicketService;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.framework.starter.idempotent.annotation.Idempotent;
import org.opengoofy.index12306.framework.starter.idempotent.enums.IdempotentSceneEnum;
import org.opengoofy.index12306.framework.starter.idempotent.enums.IdempotentTypeEnum;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退款结果回调车票消费者
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = TicketRocketMQConstant.PAY_GLOBAL_TOPIC_KEY, selectorExpression = TicketRocketMQConstant.REFUND_RESULT_CALLBACK_TICKET_TAG_KEY, consumerGroup = TicketRocketMQConstant.REFUND_RESULT_CALLBACK_TICKET_CG_KEY)
public class RefundResultCallbackTicketConsumer
    implements RocketMQListener<MessageWrapper<RefundResultCallbackTicketEvent>> {

  private final TicketService ticketService;
  private final PayRemoteService payRemoteService;

  @Idempotent(uniqueKeyPrefix = "index12306-ticket:refund_result_callback:", key = "#message.getKeys()+'_'+#message.hashCode()", type = IdempotentTypeEnum.SPEL, scene = IdempotentSceneEnum.MQ, keyTimeout = 7200L)
  @Transactional(rollbackFor = Exception.class)
  @Override
  public void onMessage(MessageWrapper<RefundResultCallbackTicketEvent> message) {
    RefundResultCallbackTicketEvent event = message.getMessage();
    RefundCallbackTicketReqDTO requestParam = new RefundCallbackTicketReqDTO();
    requestParam.setOrderSn(event.getOrderSn());
    requestParam.setRefundType(event.getRefundType());
    requestParam.setOrderItemRecordIds(event.getOrderItemRecordIds());
    ticketService.refundCallbackTicketOrder(requestParam);

    RefundTaskCallbackCompleteReqDTO callbackReq = new RefundTaskCallbackCompleteReqDTO();
    callbackReq.setRefundTaskId(event.getRefundTaskId());
    callbackReq.setCallbackType("TICKET");
    Result<Boolean> callbackResult = payRemoteService.callbackComplete(callbackReq);
    if (!callbackResult.isSuccess() || !Boolean.TRUE.equals(callbackResult.getData())) {
      throw new ServiceException("退款任务车票回写完成回执失败");
    }
  }
}
