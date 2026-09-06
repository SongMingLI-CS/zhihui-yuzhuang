package com.yuzhuang.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuzhuang.auth.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户/账号 Mapper。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
