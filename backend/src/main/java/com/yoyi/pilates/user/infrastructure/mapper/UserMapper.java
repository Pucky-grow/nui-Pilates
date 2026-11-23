package com.yoyi.pilates.user.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yoyi.pilates.user.infrastructure.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
    @Select("SELECT * FROM `user` WHERE id=#{id} AND deleted=0 FOR UPDATE")
    UserEntity lockById(@Param("id") Long id);

    @Select("SELECT * FROM `user` WHERE openid=#{openid} AND deleted=0 FOR UPDATE")
    UserEntity lockByOpenid(@Param("openid") String openid);
}
