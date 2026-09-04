package com.yuzhuang.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuzhuang.order.entity.Order;
import org.apache.ibatis.annotations.Mapper;

/**
 * 主订单 Mapper。
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {
}
