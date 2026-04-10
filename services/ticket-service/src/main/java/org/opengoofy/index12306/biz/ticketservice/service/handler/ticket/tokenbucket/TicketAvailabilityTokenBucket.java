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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.tokenbucket;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.common.enums.VehicleTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.RouteDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.SeatTypeCountDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderPassengerDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.SeatService;
import org.opengoofy.index12306.biz.ticketservice.service.TrainStationService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TokenResultDTO;
import org.opengoofy.index12306.framework.starter.bases.Singleton;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.common.toolkit.Assert;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.Index12306Constant.ADVANCE_TICKET_DAY;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_TICKET_AVAILABILITY_TOKEN_BUCKET;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TICKET_AVAILABILITY_TOKEN_BUCKET;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_INFO;

/**
 * 列车车票余量令牌桶，应对海量并发场景下满足并行、限流以及防超卖等场景
 */
@Slf4j
@Component
@RequiredArgsConstructor
public final class TicketAvailabilityTokenBucket {

    private final TrainStationService trainStationService;
    private final DistributedCache distributedCache;
    private final RedissonClient redissonClient;
    private final SeatService seatService;
    private final TrainMapper trainMapper;

    private static final String LUA_TICKET_AVAILABILITY_TOKEN_BUCKET_PATH = "lua/ticket_availability_token_bucket.lua";
    private static final String LUA_TICKET_AVAILABILITY_ROLLBACK_TOKEN_BUCKET_PATH = "lua/ticket_availability_rollback_token_bucket.lua";

    /**
     * 获取车站间令牌桶中的令牌访问
     * 如果返回 {@link Boolean#TRUE} 代表可以参与接下来的购票下单流程
     * 如果返回 {@link Boolean#FALSE} 代表当前访问出发站点和到达站点令牌已被拿完，无法参与购票下单等逻辑
     *
     * @param requestParam 购票请求参数入参
     * @return 是否获取列车车票余量令牌桶中的令牌返回结果
     */
    public TokenResultDTO takeTokenFromBucket(PurchaseTicketReqDTO requestParam) {
        TrainDO trainDO = distributedCache.safeGet(
                TRAIN_INFO + requestParam.getTrainId(),
                TrainDO.class,
                () -> trainMapper.selectById(requestParam.getTrainId()),
                ADVANCE_TICKET_DAY,
                TimeUnit.DAYS);
        if (trainDO == null) {
            throw new ServiceException("列车不存在或已下线，请重新查询后下单");
        }
        List<RouteDTO> routeDTOList = trainStationService
                .listTrainStationRoute(requestParam.getTrainId(), trainDO.getStartStation(), trainDO.getEndStation());
        if (routeDTOList == null || routeDTOList.isEmpty()) {
            throw new ServiceException("列车站点信息不存在，请稍后重试");
        }
        if (CollectionUtil.isEmpty(requestParam.getPassengers())) {
            throw new ServiceException("乘车人不能为空");
        }
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        String tokenBucketHashKey = TICKET_AVAILABILITY_TOKEN_BUCKET + requestParam.getTrainId();
        Boolean hasKey = distributedCache.hasKey(tokenBucketHashKey);
        if (!hasKey) {
            RLock lock = redissonClient
                    .getLock(String.format(LOCK_TICKET_AVAILABILITY_TOKEN_BUCKET, requestParam.getTrainId()));
            if (!lock.tryLock()) {
                throw new ServiceException("购票异常，请稍候再试");
            }
            try {
                Boolean hasKeyTwo = distributedCache.hasKey(tokenBucketHashKey);
                if (!hasKeyTwo) {
                    rebuildTokenBucket(requestParam, routeDTOList, stringRedisTemplate, tokenBucketHashKey,
                            VehicleTypeEnum.findSeatTypesByCode(trainDO.getTrainType()));
                }
            } finally {
                lock.unlock();
            }
        }
        DefaultRedisScript<String> actual = Singleton.get(LUA_TICKET_AVAILABILITY_TOKEN_BUCKET_PATH, () -> {
            DefaultRedisScript<String> redisScript = new DefaultRedisScript<>();
            redisScript.setScriptSource(
                    new ResourceScriptSource(new ClassPathResource(LUA_TICKET_AVAILABILITY_TOKEN_BUCKET_PATH)));
            redisScript.setResultType(String.class);
            return redisScript;
        });
        Assert.notNull(actual);
        Map<Integer, Long> seatTypeCountMap = requestParam.getPassengers().stream()
                .collect(Collectors.groupingBy(PurchaseTicketPassengerDetailDTO::getSeatType, Collectors.counting()));
        if (seatTypeCountMap.isEmpty()) {
            throw new ServiceException("乘车人席别信息异常，请重新选择席别后下单");
        }
        List<Integer> requestSeatTypes = Lists.newArrayList(seatTypeCountMap.keySet());
        List<Integer> trainSeatTypes = VehicleTypeEnum.findSeatTypesByCode(trainDO.getTrainType());
        JSONArray seatTypeCountArray = seatTypeCountMap.entrySet().stream()
                .map(entry -> {
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("seatType", String.valueOf(entry.getKey()));
                    jsonObject.put("count", String.valueOf(entry.getValue()));
                    return jsonObject;
                })
                .collect(Collectors.toCollection(JSONArray::new));
        List<RouteDTO> takeoutRouteDTOList = trainStationService
                .listTakeoutTrainStationRoute(requestParam.getTrainId(), requestParam.getDeparture(),
                        requestParam.getArrival());
        if (takeoutRouteDTOList == null || takeoutRouteDTOList.isEmpty()) {
            throw new ServiceException("出发站和到达站信息不匹配，请重新查询后下单");
        }
        String luaScriptKey = StrUtil.join("_", requestParam.getDeparture(), requestParam.getArrival());
        repairTokenBucketIfNecessary(requestParam, routeDTOList, takeoutRouteDTOList, requestSeatTypes,
                stringRedisTemplate, tokenBucketHashKey, luaScriptKey,
                trainSeatTypes);
        String resultStr = stringRedisTemplate.execute(actual, Lists.newArrayList(tokenBucketHashKey, luaScriptKey),
                JSON.toJSONString(seatTypeCountArray), JSON.toJSONString(takeoutRouteDTOList));
        if (StrUtil.isBlank(resultStr)) {
            return TokenResultDTO.builder().tokenIsNull(Boolean.TRUE).build();
        }
        TokenResultDTO result = JSON.parseObject(resultStr, TokenResultDTO.class);
        if (result != null && Boolean.TRUE.equals(result.getTokenIsNull())) {
            TokenResultDTO retryResult = rebuildAndRetryIfInconsistent(
                    requestParam,
                    routeDTOList,
                    requestSeatTypes,
                    seatTypeCountMap,
                    stringRedisTemplate,
                    tokenBucketHashKey,
                    actual,
                    seatTypeCountArray,
                    takeoutRouteDTOList,
                    luaScriptKey,
                    trainSeatTypes);
            if (retryResult != null) {
                return retryResult;
            }
        }
        return result == null
                ? TokenResultDTO.builder().tokenIsNull(Boolean.TRUE).build()
                : result;
    }

    private TokenResultDTO rebuildAndRetryIfInconsistent(PurchaseTicketReqDTO requestParam,
            List<RouteDTO> routeDTOList,
            List<Integer> requestSeatTypes,
            Map<Integer, Long> seatTypeCountMap,
            StringRedisTemplate stringRedisTemplate,
            String tokenBucketHashKey,
            DefaultRedisScript<String> actual,
            JSONArray seatTypeCountArray,
            List<RouteDTO> takeoutRouteDTOList,
            String luaScriptKey,
            List<Integer> trainSeatTypes) {
        List<SeatTypeCountDTO> dbSeatTypeCountDTOList = seatService.listSeatTypeCount(
                Long.parseLong(requestParam.getTrainId()), requestParam.getDeparture(), requestParam.getArrival(),
                requestSeatTypes);
        if (CollectionUtil.isEmpty(dbSeatTypeCountDTOList)) {
            return null;
        }
        Map<Integer, Integer> dbSeatTypeCountMap = dbSeatTypeCountDTOList.stream()
                .collect(Collectors.toMap(SeatTypeCountDTO::getSeatType, SeatTypeCountDTO::getSeatCount, Integer::sum));
        boolean enoughSeat = seatTypeCountMap.entrySet().stream()
                .allMatch(each -> dbSeatTypeCountMap.getOrDefault(each.getKey(), 0) >= each.getValue());
        if (!enoughSeat) {
            return null;
        }
        RLock lock = redissonClient
                .getLock(String.format(LOCK_TICKET_AVAILABILITY_TOKEN_BUCKET, requestParam.getTrainId()));
        try {
            if (!lock.tryLock(1, TimeUnit.SECONDS)) {
                return null;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        }
        try {
            distributedCache.delete(tokenBucketHashKey);
            rebuildTokenBucket(requestParam, routeDTOList, stringRedisTemplate, tokenBucketHashKey, trainSeatTypes);
            String retryResultStr = stringRedisTemplate.execute(actual,
                    Lists.newArrayList(tokenBucketHashKey, luaScriptKey),
                    JSON.toJSONString(seatTypeCountArray), JSON.toJSONString(takeoutRouteDTOList));
            if (StrUtil.isBlank(retryResultStr)) {
                return TokenResultDTO.builder().tokenIsNull(Boolean.TRUE).build();
            }
            return JSON.parseObject(retryResultStr, TokenResultDTO.class);
        } finally {
            lock.unlock();
        }
    }

    private void rebuildTokenBucket(PurchaseTicketReqDTO requestParam,
            List<RouteDTO> routeDTOList,
            StringRedisTemplate stringRedisTemplate,
            String tokenBucketHashKey,
            List<Integer> seatTypes) {
        Map<String, String> ticketAvailabilityTokenMap = new HashMap<>();
        for (RouteDTO each : routeDTOList) {
            List<SeatTypeCountDTO> seatTypeCountDTOList = seatService.listSeatTypeCount(
                    Long.parseLong(requestParam.getTrainId()), each.getStartStation(), each.getEndStation(), seatTypes);
            for (SeatTypeCountDTO eachSeatTypeCountDTO : seatTypeCountDTOList) {
                String buildCacheKey = StrUtil.join("_", each.getStartStation(), each.getEndStation(),
                        eachSeatTypeCountDTO.getSeatType());
                ticketAvailabilityTokenMap.put(buildCacheKey, String.valueOf(eachSeatTypeCountDTO.getSeatCount()));
            }
        }
        stringRedisTemplate.opsForHash().putAll(tokenBucketHashKey, ticketAvailabilityTokenMap);
    }

    private void repairTokenBucketIfNecessary(PurchaseTicketReqDTO requestParam,
            List<RouteDTO> routeDTOList,
            List<RouteDTO> takeoutRouteDTOList,
            List<Integer> requestSeatTypes,
            StringRedisTemplate stringRedisTemplate,
            String tokenBucketHashKey,
            String luaScriptKey,
            List<Integer> seatTypes) {
        List<Object> checkFields = Lists.newArrayList();
        for (Integer seatType : requestSeatTypes) {
            checkFields.add(StrUtil.join("_", luaScriptKey, seatType));
            for (RouteDTO routeDTO : takeoutRouteDTOList) {
                checkFields.add(StrUtil.join("_", routeDTO.getStartStation(), routeDTO.getEndStation(), seatType));
            }
        }
        List<Object> tokenValues = stringRedisTemplate.opsForHash().multiGet(tokenBucketHashKey, checkFields);
        boolean needRepair = tokenValues == null || tokenValues.stream().anyMatch(Objects::isNull);
        if (!needRepair) {
            return;
        }
        RLock lock = redissonClient
                .getLock(String.format(LOCK_TICKET_AVAILABILITY_TOKEN_BUCKET, requestParam.getTrainId()));
        try {
            if (!lock.tryLock(1, TimeUnit.SECONDS)) {
                return;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            List<Object> latestValues = stringRedisTemplate.opsForHash().multiGet(tokenBucketHashKey, checkFields);
            boolean stillNeedRepair = latestValues == null || latestValues.stream().anyMatch(Objects::isNull);
            if (stillNeedRepair) {
                distributedCache.delete(tokenBucketHashKey);
                rebuildTokenBucket(requestParam, routeDTOList, stringRedisTemplate, tokenBucketHashKey, seatTypes);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 回滚列车余量令牌，一般为订单取消或长时间未支付触发
     *
     * @param requestParam 回滚列车余量令牌入参
     */
    public void rollbackInBucket(TicketOrderDetailRespDTO requestParam) {
        DefaultRedisScript<Long> actual = Singleton.get(LUA_TICKET_AVAILABILITY_ROLLBACK_TOKEN_BUCKET_PATH, () -> {
            DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
            redisScript.setScriptSource(new ResourceScriptSource(
                    new ClassPathResource(LUA_TICKET_AVAILABILITY_ROLLBACK_TOKEN_BUCKET_PATH)));
            redisScript.setResultType(Long.class);
            return redisScript;
        });
        Assert.notNull(actual);
        List<TicketOrderPassengerDetailRespDTO> passengerDetails = requestParam.getPassengerDetails();
        Map<Integer, Long> seatTypeCountMap = passengerDetails.stream()
                .collect(Collectors.groupingBy(TicketOrderPassengerDetailRespDTO::getSeatType, Collectors.counting()));
        JSONArray seatTypeCountArray = seatTypeCountMap.entrySet().stream()
                .map(entry -> {
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("seatType", String.valueOf(entry.getKey()));
                    jsonObject.put("count", String.valueOf(entry.getValue()));
                    return jsonObject;
                })
                .collect(Collectors.toCollection(JSONArray::new));
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        String actualHashKey = TICKET_AVAILABILITY_TOKEN_BUCKET + requestParam.getTrainId();
        String luaScriptKey = StrUtil.join("_", requestParam.getDeparture(), requestParam.getArrival());
        List<RouteDTO> takeoutRouteDTOList = trainStationService.listTakeoutTrainStationRoute(
                String.valueOf(requestParam.getTrainId()), requestParam.getDeparture(), requestParam.getArrival());
        Long result = stringRedisTemplate.execute(actual, Lists.newArrayList(actualHashKey, luaScriptKey),
                JSON.toJSONString(seatTypeCountArray), JSON.toJSONString(takeoutRouteDTOList));
        if (result == null || !Objects.equals(result, 0L)) {
            log.error("回滚列车余票令牌失败，订单信息：{}", JSON.toJSONString(requestParam));
            throw new ServiceException("回滚列车余票令牌失败");
        }
    }

    /**
     * 删除令牌，一般在令牌与数据库不一致情况下触发
     *
     * @param requestParam 删除令牌容器参数
     */
    public void delTokenInBucket(PurchaseTicketReqDTO requestParam) {
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        String tokenBucketHashKey = TICKET_AVAILABILITY_TOKEN_BUCKET + requestParam.getTrainId();
        stringRedisTemplate.delete(tokenBucketHashKey);
    }

    public void putTokenInBucket() {

    }

    public void initializeTokens() {

    }
}
