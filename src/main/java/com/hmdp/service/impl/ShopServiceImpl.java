package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryById(Long id) {
        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);

        //逻辑过期解决缓存击穿
        Shop shop = queryWithLogicalExpire(id);
        if (shop==null) {
            return Result.fail("店铺不存在");
        }
        //返回
        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //基于逻辑过期解决缓存击穿
    public Shop queryWithLogicalExpire(Long id){
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
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1未过期，直接返回
            return shop;
        }

        //5.2过期，需要缓存重建
        //6.缓存重建
        //6.1获取互斥锁
        String lockKey=LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockKey);
        //6.2判断是否获取锁成功
        if (isLock) {
            //6.3获取成功,先再次检查Redis缓存是否过期，若不过期，则直接返回，无需缓存重建了
            queryWithLogicalExpire(id);
            //6.4若再次检查还是过期，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //缓存重建
                    System.out.println("正在重建缓存......");
                    this.saveShop2Redis(id,20L);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }

        //返回商品信息
        return shop;
    }

    //基于互斥锁解决缓存击穿
    public Shop queryWithMutex(Long id){
            String key=CACHE_SHOP_KEY + id;
            //1.从Redis查询商铺缓存
            String shopJson = stringRedisTemplate.opsForValue().get(key);
            //2.判断是否存在--是否不为空且不仅仅包含空白字符
            if (StrUtil.isNotBlank(shopJson)) {
                //3.存在，直接返回
                return JSONUtil.toBean(shopJson, Shop.class);
            }

            //判断命中的是否是空值
            if (shopJson!=null) {
                //返回一个错误信息
                return null;
            }
            //以下为不命中
            //4.实现缓存重建
            //4.1获取互斥锁
            String lockKey=LOCK_SHOP_KEY+id;
            Shop shop=null;
        try {
            boolean isLock = tryLock(lockKey);
            //4.2判断是否获取成功
            if (!isLock) {
                //4.3失败，则休眠并重试
                Thread.sleep(50);
                queryWithMutex(id);
            }
            /*获取锁成功再次检查Redis缓存是否存在，即doublecheck。
            如果存在，则无需重建缓存
            * */
//            shopJson = stringRedisTemplate.opsForValue().get(key);
//            if (StrUtil.isNotBlank(shopJson)) {
//                return JSONUtil.toBean(shopJson, Shop.class);
//            }
            queryWithMutex(id);
            //不存在，根据id查询数据库
            shop = getById(id);
            //模拟重建延时
            Thread.sleep(200);
            //5.不存在，返回错误
            if (shop==null) {
                //将空值写入Redis-----实现避免【缓存穿透】
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            //6.存在，写入Redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //7.释放互斥锁
            unlock(lockKey);
        }
            //8.返回
            return shop;

    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //1.查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id==null) {
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+shop.getId());
        return Result.ok();
    }
}
