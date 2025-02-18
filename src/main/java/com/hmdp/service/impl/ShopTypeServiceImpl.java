package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryShopType() {

        //1.从Redis查询店铺类型缓存
        String shopTypeJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopTypeJson)) {
            //存在
            List<ShopType> shopTypeList = JSONUtil.toList(shopTypeJson,ShopType.class);
            System.out.println("shopTypeList是："+shopTypeList);
            return Result.ok(shopTypeList);

        }

        //3.不存在，查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();

        //4.不存在，返回错误
        if (typeList.isEmpty()) {
            return Result.fail("找不到店铺分类！");
        }
        //6.存在，写入Redis
//        stringRedisTemplate.opsForValue().set("shop_type",typeList.toString());
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY, JSONUtil.toJsonStr(typeList));

        //返回
        return Result.ok(typeList);
    }
}
