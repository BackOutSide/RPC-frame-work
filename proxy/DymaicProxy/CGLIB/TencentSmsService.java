package com.proxy.dymaicproxy.cglib;

/**
 * 腾讯云短信服务
 */
public class TencentSmsService {
    public String send(String message) {
        System.out.println("TencentSmsService send message: " + message);
        return message;
    }
}
