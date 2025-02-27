package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT =new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private static final  ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable{
        String queueName="stream.orders";
        @Override
        public void run() {
            while (true){
                try {
                    //获取消息队列中的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //判断消息获取是否成功 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    if (list==null||list.isEmpty()) {
                        //获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    //获取成功，可以下单
                    //解析消息中的订单消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //下单
                    handleVoucherOrder(voucherOrder);

                    //ack确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());

                } catch (Exception e) {
                    //抛异常，即消息未确认,进入pending list
                    log.error("处理订单异常",e);
                    handlePendingList();
                }


            }
        }

        private void handlePendingList() {
            //
            while (true){
                try {
                    //获取pending list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //判断消息获取是否成功
                    if (list==null||list.isEmpty()) {
                        //获取失败，说明pending list没有消息，结束循环
                        break;
                    }
                    //获取成功，可以下单
                    //解析消息中的订单消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //下单
                    handleVoucherOrder(voucherOrder);

                    //ack确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());

                } catch (Exception e) {
                    //抛异常，即消息未确认,进入pending list
                    log.error("处理订单异常",e);
                    try {
                        //减少循环频率
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }


            }
        }
    }

    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        //2.判断结果是否为0
        int r = result.intValue();
        if (r!=0) {
            //2.1不为0
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }

        //获取代理对象（因为子线程无法直接获取）
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //3.返回订单id
        return Result.ok(orderId);
    }





    //用于异步执行阻塞队列里的订单
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.获取用户，子线程，无法直接通过ThreadLocal里的UserHolder获取，所以只能利用voucherOreder
        Long userId = voucherOrder.getUserId();
        //以下使用锁的步骤理论上可以省略，因为lua脚本已经对用户的购买资格做了判断，但保险起见，可以写
        //2.创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //3.获取锁
        boolean isLock = lock.tryLock();//无参，失败不等待重试，超过30s自动释放锁
        //4.判断是否获取锁成功
        if (!isLock) {
            //不成功,返回错误
            log.error("不允许重复下单");
        }
        try {
            //获取代理对象（事务）
            proxy.createVoucherOrder(voucherOrder);
        }finally {
            //释放锁
            lock.unlock();
        }
    }


    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单
        Long userId = voucherOrder.getUserId();
        int count = query().eq("user_id", userId).eq("voucher_id",voucherOrder.getVoucherId()).count();

        if (count > 0) {
            //用户已经购买
            //return Result.fail("该用户已经购买过一次");
            log.error("该用户已经购买过一次");
        }
        //解决超卖
        //5.加减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock",0)
                .update();
        if (!success) {
            //扣减失败
            //return Result.fail("库存不足");
            log.error("库存不足|");
        }
        //6.创建订单
        save(voucherOrder);
    }
}
