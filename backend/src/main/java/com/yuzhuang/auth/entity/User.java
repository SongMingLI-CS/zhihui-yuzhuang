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

    /** 创建时间 */
    private LocalDateTime createdAt;
}
