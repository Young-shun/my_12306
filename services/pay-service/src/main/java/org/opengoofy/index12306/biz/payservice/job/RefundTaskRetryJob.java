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

package org.opengoofy.index12306.biz.payservice.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.payservice.dao.entity.RefundTaskDO;
import org.opengoofy.index12306.biz.payservice.dao.mapper.RefundTaskMapper;
import org.opengoofy.index12306.biz.payservice.dto.RefundTaskDetailDTO;
import org.opengoofy.index12306.biz.payservice.mq.event.RefundTaskEvent;
import org.opengoofy.index12306.biz.payservice.mq.produce.RefundTaskSendProducer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * 退款任务重试定时任务
 * 每分钟扫描一次失败但尚未超过最大重试次数的退款任务，重新投递到 MQ
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundTaskRetryJob {

  private final RefundTaskMapper refundTaskMapper;
  private final RefundTaskSendProducer refundTaskSendProducer;

  /**
   * 每分钟执行一次（@Scheduled 采用 cron 表达式）
   */
  @Scheduled(cron = "0 * * * * ?")
  public void retryFailedRefundTasks() {
    log.debug("开始扫描需要重试的退款任务...");

    try {
      // 查询所有满足重试条件的任务
      // status=3 (失败) 且 next_retry_time <= 当前时间 且 retry_count < max_retry_count
      LambdaQueryWrapper<RefundTaskDO> queryWrapper = Wrappers.lambdaQuery(RefundTaskDO.class)
          .eq(RefundTaskDO::getStatus, 3) // 失败状态
          .le(RefundTaskDO::getNextRetryTime, new Date()) // 重试时间已到
          // retry_count < max_retry_count，按原生 SQL 列比较
          .apply("retry_count < max_retry_count")
          .orderByAsc(RefundTaskDO::getCreateTime);

      List<RefundTaskDO> failedTasks = refundTaskMapper.selectList(queryWrapper);

      if (failedTasks.isEmpty()) {
        log.debug("没有需要重试的退款任务");
        return;
      }

      log.info("发现{}个需要重试的退款任务", failedTasks.size());

      for (RefundTaskDO task : failedTasks) {
        try {
          // 重新投递到 MQ
          RefundTaskEvent retryEvent = RefundTaskEvent.builder()
              .refundTaskId(task.getRefundTaskId())
              .orderSn(task.getOrderSn())
              .paySn(task.getPaySn())
              .refundType(task.getRefundType())
              .refundAmount(task.getRefundAmount())
              // 从 JSON 反序列化退款详情
              .refundDetails(deserializeRefundDetails(task.getRefundDetail()))
              .build();

          refundTaskSendProducer.sendMessage(retryEvent);

          // 更新重试计数和下次重试时间
          int nextRetryCount = task.getRetryCount() + 1;
          long delayMs = calculateBackoffDelay(nextRetryCount);
          Date nextRetryTime = new Date(System.currentTimeMillis() + delayMs);

          RefundTaskDO updateTask = new RefundTaskDO();
          updateTask.setRetryCount(nextRetryCount);
          updateTask.setNextRetryTime(nextRetryTime);
          updateTask.setStatus(1); // 恢复为处理中状态
          LambdaUpdateWrapper<RefundTaskDO> updateWrapper = Wrappers.lambdaUpdate(RefundTaskDO.class)
              .eq(RefundTaskDO::getRefundTaskId, task.getRefundTaskId());
          refundTaskMapper.update(updateTask, updateWrapper);

          log.info("退款任务已重新投递，refundTaskId: {}, retryCount: {}/{}, nextRetryTime: {}",
              task.getRefundTaskId(), nextRetryCount, task.getMaxRetryCount(), nextRetryTime);

        } catch (Exception e) {
          log.error("重试退款任务失败，refundTaskId: {}", task.getRefundTaskId(), e);
        }
      }

    } catch (Exception e) {
      log.error("执行退款任务重试定时任务异常", e);
    }
  }

  /**
   * 计算指数退避延迟时间
   * 第1次重试：5分钟
   * 第2次重试：10分钟
   * 第3次重试：20分钟
   */
  private long calculateBackoffDelay(int retryCount) {
    // 基础延迟为 5 分钟（300000 毫秒）
    long baseDelay = 5 * 60 * 1000;
    // 指数退避：每次重试延迟时间翻倍
    return baseDelay * ((long) Math.pow(2, retryCount - 1));
  }

  /**
   * 从 JSON 字符串反序列化退款详情
   */
  private List<RefundTaskDetailDTO> deserializeRefundDetails(String refundDetailJson) {
    // 这里应该使用 JSON 反序列化工具（如 Jackson 或 Fastjson2）
    // 为了简化，这里只是一个示意
    // 实际实现应该调用 JSON.parseArray(refundDetailJson, RefundTaskDetailDTO.class)
    return com.alibaba.fastjson2.JSON.parseArray(refundDetailJson, RefundTaskDetailDTO.class);
  }
}
