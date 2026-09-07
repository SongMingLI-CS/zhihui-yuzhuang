package com.yuzhuang.order.scheduler;

import com.yuzhuang.order.service.OrderPaymentClosureService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 订单超时关单定时任务（STOCK_CONFIRMED 超时未支付 → CANCELLED + 库存回补）。
 *
 * <p>由 {@code yuzhuang.order-close.enabled} 控制（默认开启；测试环境关闭，服务层仍可直接调用）。
 * {@code fixedDelay} 保证上一轮完成后间隔再触发，避免任务堆积。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "yuzhuang.order-close", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrderCloseScheduler {

    private final OrderPaymentClosureService orderPaymentClosureService;

    @Value("${yuzhuang.order-close.batch-size:50}")
    private int batchSize;

    public OrderCloseScheduler(OrderPaymentClosureService orderPaymentClosureService) {
        this.orderPaymentClosureService = orderPaymentClosureService;
    }

    @Scheduled(
            fixedDelayString = "${yuzhuang.order-close.scan-interval-ms:60000}",
            initialDelayString = "${yuzhuang.order-close.initial-delay-ms:15000}")
    public void scanExpiredOrders() {
        try {
            int closed = orderPaymentClosureService.closeExpiredOrders(batchSize);
            if (closed > 0) {
                log.info("[order-close] scheduler closed {} expired orders", closed);
            }
        } catch (Exception e) {
            // 单轮失败记录日志，下一轮继续扫描（幂等状态门保证不重复回补）
            log.error("[order-close] scheduler scan failed, will retry next round", e);
        }
    }
}
