package com.yuzhuang.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 订单明细实体（对应 t_order_item）。
 *
 * <p>unitPrice / subtotal 为落单时点的 SKU 价格快照，避免后续改价影响历史订单。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_order_item")
public class OrderItem {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属业务订单号 */
    private String orderNo;

    /** SKU ID */
    private Long skuId;

    /** 购买数量 */
    private Integer quantity;

    /** 落单时点单价（元） */
    private BigDecimal unitPrice;

    /** 小计（单价 × 数量） */
    private BigDecimal subtotal;
}
