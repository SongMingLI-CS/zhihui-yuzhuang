package com.yuzhuang.gov.entity;

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
 * 政府/平台账号数据授权范围（对应 t_gov_scope）。
 *
 * <p>GOVERNMENT 角色本身不代表可读全部数据；必须通过本表显式授权
 * {@code REGION}（行政区域）/ {@code TENANT}（租户）/ {@code ALL} 范围，
 * 服务端据此推导只读聚合的数据边界（绝不信任请求头 tenantId）。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_gov_scope")
public class GovScope {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属租户（平台侧一般 global） */
    private String tenantId;

    /** 被授权用户主键 */
    private Long userId;

    /** 范围类型：REGION / TENANT / ALL */
    private String scopeType;

    /** 范围取值：区域名 / tenant_id / * */
    private String scopeValue;

    /** 授权操作人（平台管理员用户名） */
    private String grantedBy;

    /** 授权时间 */
    private LocalDateTime createdAt;
}
