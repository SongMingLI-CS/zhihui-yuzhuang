package com.yuzhuang.auth.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuzhuang.auth.entity.User;
import com.yuzhuang.auth.enums.UserRole;
import com.yuzhuang.auth.mapper.UserMapper;
import com.yuzhuang.auth.security.PasswordEncoder;
import com.yuzhuang.tenant.entity.Tenant;
import com.yuzhuang.tenant.mapper.TenantMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 认证演示数据初始化器（启动时幂等插入演示租户 + 演示账号）。
 *
 * <p>密码哈希由 {@link PasswordEncoder} 动态生成（含随机盐），
 * 避免在 SQL 种子中硬编码哈希；重复启动通过"存在即跳过"保证幂等。
 * 演示账号仅用于本地/演示环境，生产需替换为真实租户与账号发放流程。
 */
@Slf4j
@Component
public class AuthDataInitializer implements ApplicationRunner {

    private final TenantMapper tenantMapper;
    private final UserMapper userMapper;

    public AuthDataInitializer(TenantMapper tenantMapper, UserMapper userMapper) {
        this.tenantMapper = tenantMapper;
        this.userMapper = userMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedTenant("tenant_yuzhuang_001", "鹿邑试量镇于庄村股份经济合作社", "河南省周口市鹿邑县试量镇");
        seedUser("tenant_yuzhuang_001", "admin", "admin123", "于庄村委管理员", UserRole.VILLAGE, "13800000000");
        seedUser("tenant_yuzhuang_001", "coop001", "coop123", "于庄合作社运营", UserRole.COOPERATIVE, "13800000001");
        seedUser("tenant_yuzhuang_001", "farmer001", "farmer123", "于庄农户代表", UserRole.FARMER, "13800000002");
        log.info("[auth-init] 演示租户与账号就绪（admin/coop001/farmer001）");
    }

    private void seedTenant(String tenantId, String name, String region) {
        Tenant existing = tenantMapper.selectOne(new LambdaQueryWrapper<Tenant>()
                .eq(Tenant::getTenantId, tenantId));
        if (existing != null) {
            return;
        }
        tenantMapper.insert(Tenant.builder()
                .tenantId(tenantId)
                .name(name)
                .region(region)
                .status("ACTIVE")
                .createdAt(LocalDateTime.now())
                .build());
    }

    private void seedUser(String tenantId, String username, String rawPassword,
                          String displayName, UserRole role, String phone) {
        User existing = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));
        if (existing != null) {
            return;
        }
        userMapper.insert(User.builder()
                .tenantId(tenantId)
                .username(username)
                .passwordHash(PasswordEncoder.encode(rawPassword))
                .displayName(displayName)
                .role(role)
                .phone(phone)
                .status("ACTIVE")
                .createdAt(LocalDateTime.now())
                .build());
    }
}
