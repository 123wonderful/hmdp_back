package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        //对value序列化
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    public void setWithLogicExpire(String key, Object value,Long time,TimeUnit unit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    //解决缓存穿透
    public <R,ID> R queryWithPassThrough(String KeyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time,TimeUnit unit){
        String key=CACHE_SHOP_KEY + id;
        //1.从Redis查询缓存
        String Json = stringRedisTemplate.opsForValue().get(key);
        System.out.println(Json);
        //2.判断是否存在
        if (StrUtil.isNotBlank(Json)) {
            //3.存在，直接返回
            return JSONUtil.toBean(Json, type);
        }

        //判断命中的是否是空值
        if (Json!=null) {
            //返回一个错误信息
            return null;
        }
        //4.不存在，根据id查询数据库
//        Shop shop = getById(id);
        R r= dbFallback.apply(id);
        //5.不存在，返回错误
        if (r==null) {
            //将空值写入Redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //6.存在，写入Redis
        this.set(key,r,time,unit);
        //返回
        return r;
    }

    //解决缓存击穿
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //基于逻辑过期解决缓存击穿
    public <R,ID> R queryWithLogicalExpire(String KeyPrefix,ID id,Class<R> type,Function<ID,R> dbFalback,Long time,TimeUnit unit){
        String key=CACHE_SHOP_KEY + id;
        //1.从Redis查询店铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2.判断是否命中
        if (StrUtil.isBlank(shopJson)) {
            //3.未命中
            return null;
        }
        //4.命中，先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1未过期，直接返回
            return r;
        }

        //5.2过期，需要缓存重建
        //6.缓存重建
        //6.1获取互斥锁
        String lockKey=LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockKey);
        //6.2判断是否获取锁成功
        if (isLock) {
            //6.3获取成功,先再次检查Redis缓存是否过期，若不过期，则直接返回，无需缓存重建了
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (!StrUtil.isBlank(shopJson)) {
                redisData = JSONUtil.toBean(shopJson, RedisData.class);
                expireTime = redisData.getExpireTime();
                if (expireTime.isAfter(LocalDateTime.now())) {
                    return JSONUtil.toBean((JSONObject) redisData.getData(), type);
                }
            }
            //6.4若再次检查还是过期，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //缓存重建
                    System.out.println("正在重建缓存......");
//                    this.saveShop2Redis(id,20L);
                    //查数据库
                    R r1 = dbFalback.apply(id);
                    //写入Redis
                    this.setWithLogicExpire(key,r1,time,unit);

                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }

        //返回商品信息
        return r;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

}
