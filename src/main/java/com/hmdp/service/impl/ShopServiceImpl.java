package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.sun.org.apache.regexp.internal.RE;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

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
       //缓存穿透
        Shop shop = queryWithPassThrough(id);
        //互斥锁解决缓存击穿

        //返回
        return Result.ok(shop);
    }

    public Shop queryWithPassThrough(Long id){
            String key=CACHE_SHOP_KEY + id;
            //1.从Redis查询商铺缓存
            String shopJson = stringRedisTemplate.opsForValue().get(key);
            System.out.println(shopJson);
            //2.判断是否存在
            if (StrUtil.isNotBlank(shopJson)) {
                //3.存在，直接返回
                Shop shop = JSONUtil.toBean(shopJson, Shop.class);
                return shop;
            }

            //判断命中的是否是空值
            if (shopJson!=null) {
                //返回一个错误信息
                return null;
            }
            //4.不存在，根据id查询数据库
            Shop shop = getById(id);
            //5.不存在，返回错误
            if (shop==null) {
                //将空值写入Redis
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            //6.存在，写入Redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
            //返回
            return shop;

    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
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
