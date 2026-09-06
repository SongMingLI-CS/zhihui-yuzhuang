package com.yuzhuang.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuzhuang.tenant.entity.Tenant;
import org.apache.ibatis.annotations.Mapper;

/**
 * 租户元数据 Mapper。
 */
@Mapper
public interface TenantMapper extends BaseMapper<Tenant> {
}
