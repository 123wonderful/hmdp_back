package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @ClassName RedissonConfig
 * @Description TODO
 * @Author @xxxxxxxxxxx
 * @Date 2025/2/22 20:32
 * @Version 1.0
 */
@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(){
        //配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.6.108:6379").setPassword("200263");
        //创建RedissonCilent对象
        return Redisson.create(config);
    }

}
