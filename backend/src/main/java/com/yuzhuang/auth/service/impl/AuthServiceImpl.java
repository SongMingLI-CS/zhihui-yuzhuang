package com.yuzhuang.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuzhuang.auth.context.AuthPrincipal;
import com.yuzhuang.auth.dto.AuthLoginRequest;
import com.yuzhuang.auth.dto.AuthLoginResponse;
import com.yuzhuang.auth.dto.ChangePasswordRequest;
import com.yuzhuang.auth.dto.UserInfoResponse;
import com.yuzhuang.auth.entity.User;
import com.yuzhuang.auth.mapper.UserMapper;
import com.yuzhuang.auth.security.JwtService;
import com.yuzhuang.auth.security.PasswordEncoder;
import com.yuzhuang.auth.security.PasswordPolicy;
import com.yuzhuang.auth.service.AuthService;
import com.yuzhuang.common.enums.ResultCode;
import com.yuzhuang.common.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 认证服务实现。
 *
 * <p>登录链路：按用户名查询 → 锁定检查 → 密码常量时间比对 → 状态校验 → 失败计数/成功清零
 * → 签发 JWT。用户名/密码错误统一返回 {@code A1002}（不区分用户不存在/密码错误，防用户名枚举）。
 *
 * <p>安全增强（阶段 A）：连续失败 {@value #MAX_FAILED_ATTEMPTS} 次锁定 {@value #LOCK_MINUTES}
 * 分钟（A1005）；成功登录写 {@code lastLoginAt} 并清零失败计数；{@code mustChangePassword}
 * 随响应返回；改密走 {@link PasswordPolicy} 强度校验。
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final String ACTIVE = "ACTIVE";
    private static final String TOKEN_TYPE = "Bearer";

    /** 连续失败锁定阈值。 */
    private static final int MAX_FAILED_ATTEMPTS = 5;

    /** 锁定时长（分钟）。 */
    private static final long LOCK_MINUTES = 15;

    private final UserMapper userMapper;
    private final JwtService jwtService;

    public AuthServiceImpl(UserMapper userMapper, JwtService jwtService) {
        this.userMapper = userMapper;
        this.jwtService = jwtService;
    }

    @Override
    public AuthLoginResponse login(AuthLoginRequest request) {
        String username = request.getUsername().trim();
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));

        if (user == null) {
            // 用户不存在：统一 A1002，避免用户名枚举
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.ACCOUNT_LOCKED, "账号已锁定，请稍后再试");
        }

        if (!PasswordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            registerFailedAttempt(user);
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (!ACTIVE.equals(user.getStatus())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "账号已被停用，请联系管理员");
        }

        // 登录成功：清零失败计数、记录登录时间
        User update = new User();
        update.setId(user.getId());
        update.setFailedLoginCount(0);
        update.setLockedUntil(null);
        update.setLastLoginAt(LocalDateTime.now());
        update.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(update);

        AuthPrincipal principal = AuthPrincipal.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .tenantId(user.getTenantId())
                .role(user.getRole().name())
                .displayName(user.getDisplayName())
                .build();
        String token = jwtService.generateToken(principal);

        return AuthLoginResponse.builder()
                .token(token)
                .tokenType(TOKEN_TYPE)
                .expiresIn(jwtService.getExpireSeconds())
                .mustChangePassword(Boolean.TRUE.equals(user.getMustChangePassword()))
                .user(toUserInfo(user))
                .build();
    }

    @Override
    public void changePassword(AuthPrincipal principal, ChangePasswordRequest request) {
        if (principal == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        User user = userMapper.selectById(principal.getUserId());
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (!PasswordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "当前密码不正确");
        }
        String violation = PasswordPolicy.validate(request.getNewPassword(), user.getUsername());
        if (violation != null) {
            throw new BusinessException(ResultCode.PASSWORD_POLICY_VIOLATION, violation);
        }
        if (PasswordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new BusinessException(ResultCode.PASSWORD_POLICY_VIOLATION, "新密码不得与当前密码相同");
        }

        User update = new User();
        update.setId(user.getId());
        update.setPasswordHash(PasswordEncoder.encode(request.getNewPassword()));
        update.setMustChangePassword(false);
        update.setPasswordUpdatedAt(LocalDateTime.now());
        update.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(update);
    }

    @Override
    public UserInfoResponse me(AuthPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        User user = userMapper.selectById(principal.getUserId());
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return toUserInfo(user);
    }

    /** 失败计数 +1；达阈值写 lockedUntil。 */
    private void registerFailedAttempt(User user) {
        int failed = (user.getFailedLoginCount() == null ? 0 : user.getFailedLoginCount()) + 1;
        User update = new User();
        update.setId(user.getId());
        update.setFailedLoginCount(failed);
        if (failed >= MAX_FAILED_ATTEMPTS) {
            update.setLockedUntil(LocalDateTime.now().plusMinutes(LOCK_MINUTES));
        }
        update.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(update);
    }

    private UserInfoResponse toUserInfo(User user) {
        return UserInfoResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .displayName(user.getDisplayName())
                .tenantId(user.getTenantId())
                .role(user.getRole().name())
                .build();
    }
}
