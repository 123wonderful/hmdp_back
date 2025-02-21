package com.hmdp.utils;

public interface ILock {

    /**
    * @Description 尝试获取锁
    * @param timeoutSec
    * @return true代表获取锁成功；false代表获取锁失败
    */
    boolean tryLock(long timeoutSec);

    /**
    * @Description 释放锁
    * @param
    * @return
    */
    void unlock();

}
