package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LoginIntercertor implements HandlerInterceptor {


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        /*
        * 第一个拦截器对于未登录或登录过期的，一律放行，对于处于登录状态的则放在ThreadLocal中
        * 第二个拦截器只看得到ThreadLocal中的用户，而ThreadLocal中的用户一定是处于登录状态的，也一律放
        * 而对于某些第一个拦截器只是放行，没有放在ThreadLocal中的用户
        *   1、访问的页面不需要登录，不属于拦截器2的“管辖范围”，即不经过拦截器2，直接通过
        *   2、访问的页面需要登录，属于拦截器2的“管辖范围”，但是不在ThreadLocal中，所以过不去
        * */
        //1.判断是否需要拦截（ThreadLocal中是否有用户)
        if (UserHolder.getUser()==null){
            //没有，需要拦截,设置状态码
            response.setStatus(401);
            //拦截
            return false;
        }
        //有用户，则放行
        return true;

    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //移除用户
        UserHolder.removeUser();
    }
}
