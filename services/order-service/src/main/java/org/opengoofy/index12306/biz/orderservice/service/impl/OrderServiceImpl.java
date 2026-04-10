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

package org.opengoofy.index12306.biz.orderservice.service.impl;

import cn.crane4j.annotation.AutoOperate;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.text.StrBuilder;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.opengoofy.index12306.biz.orderservice.common.enums.OrderCanalErrorCodeEnum;
import org.opengoofy.index12306.biz.orderservice.common.enums.OrderItemStatusEnum;
import org.opengoofy.index12306.biz.orderservice.common.enums.OrderStatusEnum;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderDO;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderItemDO;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderItemPassengerDO;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderItemMapper;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderMapper;
import org.opengoofy.index12306.biz.orderservice.dto.domain.OrderStatusReversalDTO;
import org.opengoofy.index12306.biz.orderservice.dto.req.CancelTicketOrderReqDTO;
import org.opengoofy.index12306.biz.orderservice.dto.req.PurchaseTicketConflictCheckReqDTO;
import org.opengoofy.index12306.biz.orderservice.dto.req.RefundCallbackOrderUpdateReqDTO;
import org.opengoofy.index12306.biz.orderservice.dto.req.TicketOrderItemQueryReqDTO;
import org.opengoofy.index12306.biz.orderservice.dto.req.TicketOrderCreateReqDTO;
import org.opengoofy.index12306.biz.orderservice.dto.req.TicketOrderItemCreateReqDTO;
import org.opengoofy.index12306.biz.orderservice.dto.req.TicketOrderPageQueryReqDTO;
import org.opengoofy.index12306.biz.orderservice.dto.req.TicketOrderSelfPageQueryReqDTO;
import org.opengoofy.index12306.biz.orderservice.dto.resp.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.orderservice.dto.resp.TicketOrderDetailSelfRespDTO;
import org.opengoofy.index12306.biz.orderservice.dto.resp.TicketOrderPassengerDetailRespDTO;
import org.opengoofy.index12306.biz.orderservice.dto.domain.OrderItemStatusReversalDTO;
import org.opengoofy.index12306.biz.orderservice.mq.event.DelayCloseOrderEvent;
import org.opengoofy.index12306.biz.orderservice.mq.event.PayResultCallbackOrderEvent;
import org.opengoofy.index12306.biz.orderservice.mq.produce.DelayCloseOrderSendProduce;
import org.opengoofy.index12306.biz.orderservice.remote.UserRemoteService;
import org.opengoofy.index12306.biz.orderservice.remote.dto.UserQueryActualRespDTO;
import org.opengoofy.index12306.biz.orderservice.service.OrderItemService;
import org.opengoofy.index12306.biz.orderservice.service.OrderPassengerRelationService;
import org.opengoofy.index12306.biz.orderservice.service.OrderService;
import org.opengoofy.index12306.biz.orderservice.service.orderid.OrderIdGeneratorManager;
import org.opengoofy.index12306.framework.starter.common.toolkit.BeanUtil;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.convention.page.PageResponse;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.framework.starter.database.toolkit.PageUtil;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 订单服务接口层实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderItemService orderItemService;
    private final OrderPassengerRelationService orderPassengerRelationService;
    private final RedissonClient redissonClient;
    private final DelayCloseOrderSendProduce delayCloseOrderSendProduce;
    private final UserRemoteService userRemoteService;

    @Override
    public TicketOrderDetailRespDTO queryTicketOrderByOrderSn(String orderSn) {
        LambdaQueryWrapper<OrderDO> queryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getOrderSn, orderSn);
        OrderDO orderDO = orderMapper.selectOne(queryWrapper);
        TicketOrderDetailRespDTO result = BeanUtil.convert(orderDO, TicketOrderDetailRespDTO.class);
        LambdaQueryWrapper<OrderItemDO> orderItemQueryWrapper = Wrappers.lambdaQuery(OrderItemDO.class)
                .eq(OrderItemDO::getOrderSn, orderSn);
        List<OrderItemDO> orderItemDOList = orderItemMapper.selectList(orderItemQueryWrapper);
        List<TicketOrderPassengerDetailRespDTO> passengerDetails = BeanUtil.convert(orderItemDOList,
                TicketOrderPassengerDetailRespDTO.class);
        passengerDetails.forEach(each -> each.setStatusName(resolveOrderItemStatusName(each.getStatus())));
        result.setPassengerDetails(passengerDetails);
        return result;
    }

    @AutoOperate(type = TicketOrderDetailRespDTO.class, on = "data.records")
    @Override
    public PageResponse<TicketOrderDetailRespDTO> pageTicketOrder(TicketOrderPageQueryReqDTO requestParam) {
        completeExpiredPaidOrders(requestParam.getUserId());
        LambdaQueryWrapper<OrderDO> queryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getUserId, requestParam.getUserId())
                .in(OrderDO::getStatus, buildOrderStatusList(requestParam))
                .orderByDesc(OrderDO::getOrderTime);
        IPage<OrderDO> orderPage = orderMapper.selectPage(PageUtil.convert(requestParam), queryWrapper);
        return PageUtil.convert(orderPage, each -> {
            TicketOrderDetailRespDTO result = BeanUtil.convert(each, TicketOrderDetailRespDTO.class);
            LambdaQueryWrapper<OrderItemDO> orderItemQueryWrapper = Wrappers.lambdaQuery(OrderItemDO.class)
                    .eq(OrderItemDO::getOrderSn, each.getOrderSn());
            List<OrderItemDO> orderItemDOList = orderItemMapper.selectList(orderItemQueryWrapper);
            Date now = new Date();
            if (each.getArrivalTime() != null && now.after(each.getArrivalTime())) {
                orderItemDOList.forEach(item -> {
                    if (Objects.equals(item.getStatus(), OrderItemStatusEnum.ALREADY_PAID.getStatus())
                            || Objects.equals(item.getStatus(), OrderItemStatusEnum.ALREADY_PULL_IN.getStatus())) {
                        item.setStatus(OrderItemStatusEnum.ALREADY_ARRIVED.getStatus());
                    }
                });
            }
            List<TicketOrderPassengerDetailRespDTO> passengerDetails = BeanUtil.convert(orderItemDOList,
                    TicketOrderPassengerDetailRespDTO.class);
            passengerDetails.forEach(item -> item.setStatusName(resolveOrderItemStatusName(item.getStatus())));
            result.setPassengerDetails(passengerDetails);
            return result;
        });
    }

    private void completeExpiredPaidOrders(String userId) {
        if (userId == null) {
            return;
        }
        Date now = new Date();
        OrderDO updateOrderDO = new OrderDO();
        updateOrderDO.setStatus(OrderStatusEnum.COMPLETED.getStatus());
        LambdaUpdateWrapper<OrderDO> updateWrapper = Wrappers.lambdaUpdate(OrderDO.class)
                .eq(OrderDO::getUserId, userId)
                .eq(OrderDO::getStatus, OrderStatusEnum.ALREADY_PAID.getStatus())
                .lt(OrderDO::getArrivalTime, now);
        int updateCount = orderMapper.update(updateOrderDO, updateWrapper);
        if (updateCount > 0) {
            log.info("分页查询前自动补全历史订单状态，userId={}, updateCount={}", userId, updateCount);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public String createTicketOrder(TicketOrderCreateReqDTO requestParam) {
        // 通过基因法将用户 ID 融入到订单号
        String orderSn = OrderIdGeneratorManager.generateId(requestParam.getUserId());
        OrderDO orderDO = OrderDO.builder().orderSn(orderSn)
                .orderTime(requestParam.getOrderTime())
                .departure(requestParam.getDeparture())
                .departureTime(requestParam.getDepartureTime())
                .ridingDate(requestParam.getRidingDate())
                .arrivalTime(requestParam.getArrivalTime())
                .trainNumber(requestParam.getTrainNumber())
                .arrival(requestParam.getArrival())
                .trainId(requestParam.getTrainId())
                .source(requestParam.getSource())
                .status(OrderStatusEnum.PENDING_PAYMENT.getStatus())
                .username(requestParam.getUsername())
                .userId(String.valueOf(requestParam.getUserId()))
                .build();
        orderMapper.insert(orderDO);
        List<TicketOrderItemCreateReqDTO> ticketOrderItems = requestParam.getTicketOrderItems();
        List<OrderItemDO> orderItemDOList = new ArrayList<>();
        List<OrderItemPassengerDO> orderPassengerRelationDOList = new ArrayList<>();
        ticketOrderItems.forEach(each -> {
            OrderItemDO orderItemDO = OrderItemDO.builder()
                    .trainId(requestParam.getTrainId())
                    .seatNumber(each.getSeatNumber())
                    .carriageNumber(each.getCarriageNumber())
                    .realName(each.getRealName())
                    .orderSn(orderSn)
                    .phone(each.getPhone())
                    .seatType(each.getSeatType())
                    .username(requestParam.getUsername()).amount(each.getAmount())
                    .carriageNumber(each.getCarriageNumber())
                    .idCard(each.getIdCard())
                    .ticketType(each.getTicketType())
                    .idType(each.getIdType())
                    .userId(String.valueOf(requestParam.getUserId()))
                    .status(0)
                    .build();
            orderItemDOList.add(orderItemDO);
            OrderItemPassengerDO orderPassengerRelationDO = OrderItemPassengerDO.builder()
                    .idType(each.getIdType())
                    .idCard(each.getIdCard())
                    .orderSn(orderSn)
                    .build();
            orderPassengerRelationDOList.add(orderPassengerRelationDO);
        });
        orderItemService.saveBatch(orderItemDOList);
        orderPassengerRelationService.saveBatch(orderPassengerRelationDOList);
        try {
            // 发送 RocketMQ 延时消息，指定时间后取消订单
            DelayCloseOrderEvent delayCloseOrderEvent = DelayCloseOrderEvent.builder()
                    .trainId(String.valueOf(requestParam.getTrainId()))
                    .departure(requestParam.getDeparture())
                    .arrival(requestParam.getArrival())
                    .orderSn(orderSn)
                    .trainPurchaseTicketResults(requestParam.getTicketOrderItems())
                    .build();
            // 创建订单并支付后延时关闭订单消息怎么办？详情查看：https://nageoffer.com/12306/question
            SendResult sendResult = delayCloseOrderSendProduce.sendMessage(delayCloseOrderEvent);
            if (!Objects.equals(sendResult.getSendStatus(), SendStatus.SEND_OK)) {
                throw new ServiceException("投递延迟关闭订单消息队列失败");
            }
        } catch (Throwable ex) {
            log.error("延迟关闭订单消息队列发送错误，请求参数：{}", JSON.toJSONString(requestParam), ex);
            throw ex;
        }
        return orderSn;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean closeTickOrder(CancelTicketOrderReqDTO requestParam) {
        String orderSn = requestParam.getOrderSn();
        LambdaQueryWrapper<OrderDO> queryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getOrderSn, orderSn)
                .select(OrderDO::getStatus);
        OrderDO orderDO = orderMapper.selectOne(queryWrapper);
        if (Objects.isNull(orderDO) || orderDO.getStatus() != OrderStatusEnum.PENDING_PAYMENT.getStatus()) {
            return false;
        }
        // 原则上订单关闭和订单取消这两个方法可以复用，为了区分未来考虑到的场景，这里对方法进行拆分但复用逻辑
        return cancelTickOrder(requestParam);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean cancelTickOrder(CancelTicketOrderReqDTO requestParam) {
        String orderSn = requestParam.getOrderSn();
        LambdaQueryWrapper<OrderDO> queryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getOrderSn, orderSn);
        OrderDO orderDO = orderMapper.selectOne(queryWrapper);
        if (orderDO == null) {
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_UNKNOWN_ERROR);
        } else if (Objects.equals(orderDO.getStatus(), OrderStatusEnum.CLOSED.getStatus())) {
            return true;
        } else if (orderDO.getStatus() != OrderStatusEnum.PENDING_PAYMENT.getStatus()) {
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_STATUS_ERROR);
        }
        RLock lock = redissonClient.getLock(StrBuilder.create("order:canal:order_sn_").append(orderSn).toString());
        if (!lock.tryLock()) {
            throw new ClientException(OrderCanalErrorCodeEnum.ORDER_CANAL_REPETITION_ERROR);
        }
        try {
            OrderDO updateOrderDO = new OrderDO();
            updateOrderDO.setStatus(OrderStatusEnum.CLOSED.getStatus());
            LambdaUpdateWrapper<OrderDO> updateWrapper = Wrappers.lambdaUpdate(OrderDO.class)
                    .eq(OrderDO::getOrderSn, orderSn);
            int updateResult = orderMapper.update(updateOrderDO, updateWrapper);
            if (updateResult <= 0) {
                throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_ERROR);
            }
            OrderItemDO updateOrderItemDO = new OrderItemDO();
            updateOrderItemDO.setStatus(OrderItemStatusEnum.CLOSED.getStatus());
            LambdaUpdateWrapper<OrderItemDO> updateItemWrapper = Wrappers.lambdaUpdate(OrderItemDO.class)
                    .eq(OrderItemDO::getOrderSn, orderSn);
            int updateItemResult = orderItemMapper.update(updateOrderItemDO, updateItemWrapper);
            if (updateItemResult <= 0) {
                throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_ERROR);
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
        return true;
    }

    @Override
    public void statusReversal(OrderStatusReversalDTO requestParam) {
        LambdaQueryWrapper<OrderDO> queryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getOrderSn, requestParam.getOrderSn());
        OrderDO orderDO = orderMapper.selectOne(queryWrapper);
        if (orderDO == null) {
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_UNKNOWN_ERROR);
        } else if (Objects.equals(orderDO.getStatus(), requestParam.getOrderStatus())) {
            return;
        } else if (orderDO.getStatus() != OrderStatusEnum.PENDING_PAYMENT.getStatus()) {
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_STATUS_ERROR);
        }
        RLock lock = redissonClient.getLock(
                StrBuilder.create("order:status-reversal:order_sn_").append(requestParam.getOrderSn()).toString());
        if (!lock.tryLock()) {
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_REPETITION_ERROR);
        }
        try {
            OrderDO updateOrderDO = new OrderDO();
            updateOrderDO.setStatus(requestParam.getOrderStatus());
            LambdaUpdateWrapper<OrderDO> updateWrapper = Wrappers.lambdaUpdate(OrderDO.class)
                    .eq(OrderDO::getOrderSn, requestParam.getOrderSn());
            int updateResult = orderMapper.update(updateOrderDO, updateWrapper);
            if (updateResult <= 0) {
                throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_STATUS_REVERSAL_ERROR);
            }
            OrderItemDO orderItemDO = new OrderItemDO();
            orderItemDO.setStatus(requestParam.getOrderItemStatus());
            LambdaUpdateWrapper<OrderItemDO> orderItemUpdateWrapper = Wrappers.lambdaUpdate(OrderItemDO.class)
                    .eq(OrderItemDO::getOrderSn, requestParam.getOrderSn());
            int orderItemUpdateResult = orderItemMapper.update(orderItemDO, orderItemUpdateWrapper);
            if (orderItemUpdateResult <= 0) {
                throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_STATUS_REVERSAL_ERROR);
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public void payCallbackOrder(PayResultCallbackOrderEvent requestParam) {
        OrderDO updateOrderDO = new OrderDO();
        updateOrderDO.setPayTime(requestParam.getGmtPayment());
        updateOrderDO.setPayType(requestParam.getChannel());
        LambdaUpdateWrapper<OrderDO> updateWrapper = Wrappers.lambdaUpdate(OrderDO.class)
                .eq(OrderDO::getOrderSn, requestParam.getOrderSn());
        int updateResult = orderMapper.update(updateOrderDO, updateWrapper);
        if (updateResult <= 0) {
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_STATUS_REVERSAL_ERROR);
        }
    }

    @AutoOperate(type = TicketOrderDetailSelfRespDTO.class, on = "data.records")
    @Override
    public PageResponse<TicketOrderDetailSelfRespDTO> pageSelfTicketOrder(TicketOrderSelfPageQueryReqDTO requestParam) {
        Result<UserQueryActualRespDTO> userActualResp = userRemoteService
                .queryActualUserByUsername(UserContext.getUsername());
        if (!userActualResp.isSuccess() || userActualResp.getData() == null) {
            throw new ServiceException("查询当前用户实名信息失败");
        }
        String idCard = userActualResp.getData().getIdCard();
        Set<String> orderSnSet = listSelfTicketOrderSnSet(requestParam);
        if (orderSnSet.isEmpty()) {
            return PageResponse.<TicketOrderDetailSelfRespDTO>builder()
                    .current(requestParam.getCurrent())
                    .size(requestParam.getSize())
                    .total(0L)
                    .records(List.of())
                    .build();
        }
        LambdaQueryWrapper<OrderItemDO> orderItemQueryWrapper = Wrappers.lambdaQuery(OrderItemDO.class)
                .eq(OrderItemDO::getIdCard, idCard)
                .in(OrderItemDO::getOrderSn, orderSnSet)
                .orderByDesc(OrderItemDO::getCreateTime);
        if (requestParam.getTicketType() != null) {
            orderItemQueryWrapper.eq(OrderItemDO::getTicketType, requestParam.getTicketType());
        }
        IPage<OrderItemDO> orderItemPage = orderItemMapper.selectPage(PageUtil.convert(requestParam),
                orderItemQueryWrapper);
        Set<String> pageOrderSnSet = orderItemPage.getRecords().stream()
                .map(OrderItemDO::getOrderSn)
                .collect(Collectors.toSet());
        if (pageOrderSnSet.isEmpty()) {
            return PageResponse.<TicketOrderDetailSelfRespDTO>builder()
                    .current(orderItemPage.getCurrent())
                    .size(orderItemPage.getSize())
                    .total(orderItemPage.getTotal())
                    .records(List.of())
                    .build();
        }
        LambdaQueryWrapper<OrderDO> orderQueryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                .in(OrderDO::getOrderSn, pageOrderSnSet);
        Map<String, OrderDO> orderDOMap = orderMapper.selectList(orderQueryWrapper).stream()
                .collect(Collectors.toMap(OrderDO::getOrderSn, Function.identity(), (left, right) -> left));
        List<TicketOrderDetailSelfRespDTO> records = orderItemPage.getRecords().stream().map(orderItemDO -> {
            OrderDO orderDO = orderDOMap.get(orderItemDO.getOrderSn());
            if (orderDO == null) {
                return null;
            }
            TicketOrderDetailSelfRespDTO actualResult = BeanUtil.convert(orderDO, TicketOrderDetailSelfRespDTO.class);
            BeanUtil.convertIgnoreNullAndBlank(orderItemDO, actualResult);
            Integer ticketStatus = orderItemDO.getStatus();
            if ((Objects.equals(ticketStatus, OrderItemStatusEnum.ALREADY_PAID.getStatus())
                    || Objects.equals(ticketStatus, OrderItemStatusEnum.ALREADY_PULL_IN.getStatus()))
                    && orderDO.getArrivalTime() != null
                    && new Date().after(orderDO.getArrivalTime())) {
                ticketStatus = OrderItemStatusEnum.ALREADY_ARRIVED.getStatus();
            }
            actualResult.setTicketStatus(ticketStatus);
            actualResult.setTicketStatusName(resolveOrderItemStatusName(ticketStatus));
            return actualResult;
        }).filter(Objects::nonNull).toList();
        return PageResponse.<TicketOrderDetailSelfRespDTO>builder()
                .current(orderItemPage.getCurrent())
                .size(orderItemPage.getSize())
                .total(orderItemPage.getTotal())
                .records(records)
                .build();
    }

    private Set<String> listSelfTicketOrderSnSet(TicketOrderSelfPageQueryReqDTO requestParam) {
        LambdaQueryWrapper<OrderDO> orderQueryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getUserId, UserContext.getUserId())
                .select(OrderDO::getOrderSn);
        if (requestParam.getStartRidingDate() != null) {
            orderQueryWrapper.ge(OrderDO::getRidingDate,
                    DateUtil.format(requestParam.getStartRidingDate(), DatePattern.NORM_DATE_PATTERN));
        }
        if (requestParam.getEndRidingDate() != null) {
            Date endRidingDateNextDay = DateUtil.offsetDay(requestParam.getEndRidingDate(), 1);
            orderQueryWrapper.lt(OrderDO::getRidingDate,
                    DateUtil.format(endRidingDateNextDay, DatePattern.NORM_DATE_PATTERN));
        }
        List<OrderDO> orderDOList = orderMapper.selectList(orderQueryWrapper);
        return orderDOList.stream().map(OrderDO::getOrderSn).collect(Collectors.toSet());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refundCallbackOrder(RefundCallbackOrderUpdateReqDTO requestParam) {
        if (requestParam.getRefundType() != null && requestParam.getRefundType() == 1) {
            statusReversal(OrderStatusReversalDTO.builder()
                    .orderSn(requestParam.getOrderSn())
                    .orderStatus(OrderStatusEnum.FULL_REFUND.getStatus())
                    .orderItemStatus(OrderItemStatusEnum.REFUNDED.getStatus())
                    .build());
            return;
        }
        if (requestParam.getOrderItemRecordIds() == null || requestParam.getOrderItemRecordIds().isEmpty()) {
            throw new ServiceException("部分退款缺少子订单记录");
        }
        TicketOrderItemQueryReqDTO queryReqDTO = new TicketOrderItemQueryReqDTO();
        queryReqDTO.setOrderSn(requestParam.getOrderSn());
        try {
            queryReqDTO.setOrderItemRecordIds(requestParam.getOrderItemRecordIds().stream()
                    .map(Long::valueOf)
                    .toList());
        } catch (NumberFormatException ex) {
            throw new ServiceException("部分退款子订单记录格式错误");
        }
        List<TicketOrderPassengerDetailRespDTO> ticketItems = orderItemService.queryTicketItemOrderById(queryReqDTO);
        List<OrderItemDO> orderItemDOList = ticketItems.stream().map(each -> {
            OrderItemDO orderItemDO = new OrderItemDO();
            if (each.getId() != null) {
                orderItemDO.setId(Long.valueOf(each.getId()));
            }
            orderItemDO.setRealName(each.getRealName());
            orderItemDO.setIdCard(each.getIdCard());
            return orderItemDO;
        }).toList();
        orderItemService.orderItemStatusReversal(OrderItemStatusReversalDTO.builder()
                .orderSn(requestParam.getOrderSn())
                .orderStatus(null)
                .orderItemStatus(OrderItemStatusEnum.REFUNDED.getStatus())
                .orderItemDOList(orderItemDOList)
                .build());

        LambdaQueryWrapper<OrderItemDO> allOrderItemsQueryWrapper = Wrappers.lambdaQuery(OrderItemDO.class)
                .eq(OrderItemDO::getOrderSn, requestParam.getOrderSn())
                .select(OrderItemDO::getStatus);
        List<OrderItemDO> allOrderItems = orderItemMapper.selectList(allOrderItemsQueryWrapper);
        boolean fullRefund = !allOrderItems.isEmpty() && allOrderItems.stream()
                .allMatch(each -> Objects.equals(each.getStatus(), OrderItemStatusEnum.REFUNDED.getStatus()));
        Integer targetOrderStatus = fullRefund
                ? OrderStatusEnum.FULL_REFUND.getStatus()
                : OrderStatusEnum.PARTIAL_REFUND.getStatus();
        OrderDO updateOrderDO = new OrderDO();
        updateOrderDO.setStatus(targetOrderStatus);
        LambdaUpdateWrapper<OrderDO> updateOrderWrapper = Wrappers.lambdaUpdate(OrderDO.class)
                .eq(OrderDO::getOrderSn, requestParam.getOrderSn());
        int orderUpdateResult = orderMapper.update(updateOrderDO, updateOrderWrapper);
        if (orderUpdateResult <= 0) {
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_STATUS_REVERSAL_ERROR);
        }
    }

    @Override
    public boolean hasTicketConflict(PurchaseTicketConflictCheckReqDTO requestParam) {
        if (requestParam.getDepartureTime() == null || requestParam.getArrivalTime() == null
                || requestParam.getRidingDate() == null
                || requestParam.getIdCardList() == null || requestParam.getIdCardList().isEmpty()) {
            return false;
        }
        log.info("冲突校验输入: ridingDate={}, departureTime={}, arrivalTime={}, idCardList={}",
                requestParam.getRidingDate(), requestParam.getDepartureTime(), requestParam.getArrivalTime(),
                requestParam.getIdCardList());
        // 先通过乘车人关系表按 id_card（分片键）查订单号，避免 t_order_item 无分片键导致的全路由。
        LambdaQueryWrapper<OrderItemPassengerDO> passengerQueryWrapper = Wrappers
                .lambdaQuery(OrderItemPassengerDO.class)
                .in(OrderItemPassengerDO::getIdCard, requestParam.getIdCardList())
                .select(OrderItemPassengerDO::getOrderSn);
        List<OrderItemPassengerDO> orderPassengers = orderPassengerRelationService.list(passengerQueryWrapper);
        if (orderPassengers == null || orderPassengers.isEmpty()) {
            return false;
        }
        Set<String> orderSnSet = orderPassengers.stream().map(OrderItemPassengerDO::getOrderSn)
                .collect(Collectors.toSet());
        if (orderSnSet.isEmpty()) {
            return false;
        }
        // 以乘车人维度的订单明细状态为准，避免部分退款场景下按订单状态误判。
        LambdaQueryWrapper<OrderItemDO> activeOrderItemQueryWrapper = Wrappers.lambdaQuery(OrderItemDO.class)
                .in(OrderItemDO::getOrderSn, orderSnSet)
                .in(OrderItemDO::getIdCard, requestParam.getIdCardList())
                .in(OrderItemDO::getStatus,
                        OrderItemStatusEnum.PENDING_PAYMENT.getStatus(),
                        OrderItemStatusEnum.ALREADY_PAID.getStatus(),
                        OrderItemStatusEnum.ALREADY_PULL_IN.getStatus())
                .select(OrderItemDO::getOrderSn);
        List<OrderItemDO> activeOrderItems = orderItemMapper.selectList(activeOrderItemQueryWrapper);
        if (activeOrderItems == null || activeOrderItems.isEmpty()) {
            return false;
        }
        Set<String> activeOrderSnSet = activeOrderItems.stream()
                .map(OrderItemDO::getOrderSn)
                .collect(Collectors.toSet());
        if (activeOrderSnSet.isEmpty()) {
            return false;
        }
        LambdaQueryWrapper<OrderDO> orderQueryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                .in(OrderDO::getOrderSn, activeOrderSnSet)
                .select(OrderDO::getRidingDate, OrderDO::getDepartureTime, OrderDO::getArrivalTime);
        List<OrderDO> orderDOList = orderMapper.selectList(orderQueryWrapper);
        Date targetDepartureTime = requestParam.getDepartureTime();
        Date targetArrivalTime = requestParam.getArrivalTime();
        Date targetRidingDate = requestParam.getRidingDate();
        List<OrderDO> sameDayOrders = orderDOList.stream()
                .filter(each -> isSameDay(each.getRidingDate(), targetRidingDate))
                .toList();
        log.info("冲突校验候选订单: allOrderCount={}, sameDayOrderCount={}, sameDayOrders={}",
                orderDOList.size(),
                sameDayOrders.size(),
                sameDayOrders.stream().map(each -> StrBuilder.create()
                        .append("[ridingDate=").append(each.getRidingDate())
                        .append(", departureTime=").append(each.getDepartureTime())
                        .append(", arrivalTime=").append(each.getArrivalTime())
                        .append("]").toString()).toList());
        boolean conflict = sameDayOrders.stream().anyMatch(each -> isTimeOverlap(
                each.getDepartureTime(),
                each.getArrivalTime(),
                targetDepartureTime,
                targetArrivalTime));
        log.info("冲突校验结果: conflict={}", conflict);
        return conflict;
    }

    private boolean isTimeOverlap(Date startA, Date endA, Date startB, Date endB) {
        if (startA == null || endA == null || startB == null || endB == null) {
            return false;
        }
        long startATime = startA.getTime();
        long endATime = endA.getTime();
        long startBTime = startB.getTime();
        long endBTime = endB.getTime();
        return startATime < endBTime && startBTime < endATime;
    }

    private boolean isSameDay(Date first, Date second) {
        if (first == null || second == null) {
            return false;
        }
        Calendar c1 = Calendar.getInstance();
        c1.setTime(first);
        Calendar c2 = Calendar.getInstance();
        c2.setTime(second);
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR)
                && c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR);
    }

    private String resolveOrderItemStatusName(Integer ticketStatus) {
        if (ticketStatus == null) {
            return null;
        }
        for (OrderItemStatusEnum each : OrderItemStatusEnum.values()) {
            if (Objects.equals(each.getStatus(), ticketStatus)) {
                return each.getStatusName();
            }
        }
        return null;
    }

    private List<Integer> buildOrderStatusList(TicketOrderPageQueryReqDTO requestParam) {
        List<Integer> result = new ArrayList<>();
        switch (requestParam.getStatusType()) {
            case 0 -> result = ListUtil.of(
                    OrderStatusEnum.PENDING_PAYMENT.getStatus());
            case 1 -> result = ListUtil.of(
                    OrderStatusEnum.ALREADY_PAID.getStatus(),
                    OrderStatusEnum.PARTIAL_REFUND.getStatus(),
                    OrderStatusEnum.FULL_REFUND.getStatus());
            case 2 -> result = ListUtil.of(
                    OrderStatusEnum.COMPLETED.getStatus());
        }
        return result;
    }
}
