package com.yuzhuang.tenant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 租户元数据实体（对应 t_tenant）。
 *
 * <p>一村/一合作社一租户；{@code tenant_id} 全局唯一，业务表以该标识做行级隔离。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_tenant")
public class Tenant {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 租户标识（全局唯一，如 tenant_yuzhuang_001） */
    private String tenantId;

    /** 租户名称 */
    private String name;

    /** 属地（行政区划） */
    private String region;

    /** 状态：ACTIVE 启用 / DISABLED 停用 */
    private String status;

    /** 租户类型：VILLAGE 村 / COOPERATIVE 合作社 / PLATFORM 平台 */
    private String tenantType;

    /** 上级行政区域（政府聚合范围解析用） */
    private String parentRegion;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
