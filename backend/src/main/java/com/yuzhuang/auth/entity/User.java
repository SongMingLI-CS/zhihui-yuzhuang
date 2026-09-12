package com.yuzhuang.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yuzhuang.auth.enums.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 用户/账号实体（对应 t_user）。
 *
 * <p>用户按 {@code tenant_id} 归属租户；{@code username} 全局唯一。
 * {@code role} 区分农户/合作社/村委，存储层以枚举名落库。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_user")
public class User {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属租户标识 */
    private String tenantId;

    /** 登录用户名（全局唯一） */
    private String username;

    /** PBKDF2WithHmacSHA256 密码哈希 */
    private String passwordHash;

    /** 显示名称 */
    private String displayName;

    /** 角色（FARMER / COOPERATIVE / VILLAGE） */
    private UserRole role;

    /** 手机号 */
    private String phone;

    /** 状态：ACTIVE 启用 / DISABLED 停用 */
    private String status;

    /** 是否强制下次登录改密（演示账号/管理员重置后为 true） */
    private Boolean mustChangePassword;

    /** 最近一次密码变更时间 */
    private LocalDateTime passwordUpdatedAt;

    /** 最近一次成功登录时间 */
    private LocalDateTime lastLoginAt;

    /** 停用时间 */
    private LocalDateTime disabledAt;

    /** 停用原因（审计留痕） */
    private String disabledReason;

    /** 连续登录失败次数（成功后清零） */
    private Integer failedLoginCount;

    /** 锁定到期时间（连续失败触发，期间拒绝登录） */
    private LocalDateTime lockedUntil;

    /** 账号创建者（平台管理员用户名） */
    private String createdBy;

    /** 最近更新时间 */
    private LocalDateTime updatedAt;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
