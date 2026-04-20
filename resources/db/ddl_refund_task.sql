-- 退款任务表（异步化改造）
-- 用于支持异步退款流程，ticket 写任务，pay 消费处理
-- 显式在两个支付分库创建，避免依赖默认库

USE 12306_pay_0;

CREATE TABLE IF NOT EXISTS `t_refund_task`
(
    `id`                  bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `refund_task_id`      varchar(64)  NOT NULL COMMENT '退款任务唯一标识',
    `order_sn`            varchar(64)  NOT NULL COMMENT '订单号',
    `pay_sn`              varchar(64)  DEFAULT NULL COMMENT '支付流水号',
    `refund_type`         int(3) DEFAULT NULL COMMENT '退款类型: 0-部分退款 1-全部退款',
    `refund_amount`       int(11) DEFAULT NULL COMMENT '退款金额(分)',
    `refund_detail`       longtext COMMENT '退款明细(JSON格式,包含乘客信息)',
    `status`              int(3) NOT NULL DEFAULT 0 COMMENT '任务状态: 0-待处理 1-处理中 2-成功 3-失败',
    `error_message`       varchar(500) DEFAULT NULL COMMENT '失败原因',
    `refund_result`       longtext COMMENT '退款结果(第三方返回)',
    `retry_count`         int(3) DEFAULT 0 COMMENT '重试次数',
    `max_retry_count`     int(3) DEFAULT 3 COMMENT '最大重试次数',
    `next_retry_time`     datetime DEFAULT NULL COMMENT '下次重试时间',
    `create_time`         datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`         datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `del_flag`            tinyint(1) DEFAULT 0 COMMENT '删除标记 0:未删除 1:删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_refund_task_id` (`refund_task_id`) USING BTREE,
    KEY `idx_order_sn` (`order_sn`) USING BTREE,
    KEY `idx_status` (`status`) USING BTREE,
    KEY `idx_create_time` (`create_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退款任务表';

USE 12306_pay_1;

CREATE TABLE IF NOT EXISTS `t_refund_task`
(
    `id`                  bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `refund_task_id`      varchar(64)  NOT NULL COMMENT '退款任务唯一标识',
    `order_sn`            varchar(64)  NOT NULL COMMENT '订单号',
    `pay_sn`              varchar(64)  DEFAULT NULL COMMENT '支付流水号',
    `refund_type`         int(3) DEFAULT NULL COMMENT '退款类型: 0-部分退款 1-全部退款',
    `refund_amount`       int(11) DEFAULT NULL COMMENT '退款金额(分)',
    `refund_detail`       longtext COMMENT '退款明细(JSON格式,包含乘客信息)',
    `status`              int(3) NOT NULL DEFAULT 0 COMMENT '任务状态: 0-待处理 1-处理中 2-成功 3-失败',
    `error_message`       varchar(500) DEFAULT NULL COMMENT '失败原因',
    `refund_result`       longtext COMMENT '退款结果(第三方返回)',
    `retry_count`         int(3) DEFAULT 0 COMMENT '重试次数',
    `max_retry_count`     int(3) DEFAULT 3 COMMENT '最大重试次数',
    `next_retry_time`     datetime DEFAULT NULL COMMENT '下次重试时间',
    `create_time`         datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`         datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `del_flag`            tinyint(1) DEFAULT 0 COMMENT '删除标记 0:未删除 1:删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_refund_task_id` (`refund_task_id`) USING BTREE,
    KEY `idx_order_sn` (`order_sn`) USING BTREE,
    KEY `idx_status` (`status`) USING BTREE,
    KEY `idx_create_time` (`create_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退款任务表';
