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

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.opengoofy.index12306.biz.ticketservice.common.constant.TicketRocketMQConstant;
import org.opengoofy.index12306.biz.ticketservice.common.enums.TicketStatusEnum;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TicketDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TicketMapper;
import org.opengoofy.index12306.biz.ticketservice.mq.domain.MessageWrapper;
import org.opengoofy.index12306.biz.ticketservice.mq.event.PayResultCallbackTicketEvent;
import org.opengoofy.index12306.biz.ticketservice.remote.PayRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.PayTaskCallbackCompleteReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.TicketOrderRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderPassengerDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.SeatService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.framework.starter.common.toolkit.BeanUtil;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.framework.starter.idempotent.annotation.Idempotent;
import org.opengoofy.index12306.framework.starter.idempotent.enums.IdempotentSceneEnum;
import org.opengoofy.index12306.framework.starter.idempotent.enums.IdempotentTypeEnum;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * 支付结果回调购票消费者
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = TicketRocketMQConstant.PAY_GLOBAL_TOPIC_KEY, selectorExpression = TicketRocketMQConstant.PAY_RESULT_CALLBACK_TICKET_TAG_KEY, consumerGroup = TicketRocketMQConstant.PAY_RESULT_CALLBACK_TICKET_CG_KEY)
public class PayResultCallbackTicketConsumer implements RocketMQListener<MessageWrapper<PayResultCallbackTicketEvent>> {

    private final TicketOrderRemoteService ticketOrderRemoteService;
    private final SeatService seatService;
    private final TicketMapper ticketMapper;
    private final PayRemoteService payRemoteService;

    @Idempotent(uniqueKeyPrefix = "index12306-ticket:pay_result_callback:", key = "#message.getKeys()+'_'+#message.hashCode()", type = IdempotentTypeEnum.SPEL, scene = IdempotentSceneEnum.MQ, keyTimeout = 7200L)
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void onMessage(MessageWrapper<PayResultCallbackTicketEvent> message) {
        Result<TicketOrderDetailRespDTO> ticketOrderDetailResult;
        try {
            ticketOrderDetailResult = ticketOrderRemoteService
                    .queryTicketOrderByOrderSn(message.getMessage().getOrderSn());
            if (!ticketOrderDetailResult.isSuccess() || Objects.isNull(ticketOrderDetailResult.getData())) {
                throw new ServiceException("支付结果回调查询订单失败");
            }
        } catch (Throwable ex) {
            log.error("支付结果回调查询订单失败", ex);
            throw ex;
        }
        TicketOrderDetailRespDTO ticketOrderDetail = ticketOrderDetailResult.getData();
        seatService.markSold(
                String.valueOf(ticketOrderDetail.getTrainId()),
                ticketOrderDetail.getDeparture(),
                ticketOrderDetail.getArrival(),
                BeanUtil.convert(ticketOrderDetail.getPassengerDetails(), TrainPurchaseTicketRespDTO.class));
        for (TicketOrderPassengerDetailRespDTO each : ticketOrderDetail.getPassengerDetails()) {
            LambdaUpdateWrapper<TicketDO> ticketUpdateWrapper = Wrappers.lambdaUpdate(TicketDO.class)
                    .eq(TicketDO::getTrainId, ticketOrderDetail.getTrainId())
                    .eq(TicketDO::getUsername, each.getUsername())
                    .eq(TicketDO::getCarriageNumber, each.getCarriageNumber())
                    .eq(TicketDO::getSeatNumber, each.getSeatNumber())
                    .eq(TicketDO::getTicketStatus, TicketStatusEnum.UNPAID.getCode());
            TicketDO updateTicketDO = new TicketDO();
            updateTicketDO.setTicketStatus(TicketStatusEnum.PAID.getCode());
            int updateRows = ticketMapper.update(updateTicketDO, ticketUpdateWrapper);
            if (updateRows <= 0) {
                LambdaUpdateWrapper<TicketDO> fallbackWrapper = Wrappers.lambdaUpdate(TicketDO.class)
                        .eq(TicketDO::getTrainId, ticketOrderDetail.getTrainId())
                        .eq(TicketDO::getUsername, each.getUsername())
                        .eq(TicketDO::getCarriageNumber, each.getCarriageNumber())
                        .eq(TicketDO::getSeatNumber, each.getSeatNumber())
                        .ne(TicketDO::getTicketStatus, TicketStatusEnum.PAID.getCode());
                int fallbackRows = ticketMapper.update(updateTicketDO, fallbackWrapper);
                log.warn(
                        "支付回调更新 ticket_status 命中0行，触发兜底更新。orderSn={}, username={}, carriage={}, seat={}, fallbackRows={}",
                        ticketOrderDetail.getOrderSn(), each.getUsername(), each.getCarriageNumber(),
                        each.getSeatNumber(), fallbackRows);
            }
        }

        PayTaskCallbackCompleteReqDTO callbackReq = new PayTaskCallbackCompleteReqDTO();
        callbackReq.setOrderSn(ticketOrderDetail.getOrderSn());
        callbackReq.setCallbackType("TICKET");
        Result<Boolean> callbackResult = payRemoteService.callbackPayComplete(callbackReq);
        if (!callbackResult.isSuccess()) {
            throw new ServiceException("支付任务票务回写完成回执失败");
        }
    }
}
