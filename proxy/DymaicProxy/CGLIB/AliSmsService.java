package com.proxy.dymaicproxy.cglib;

/**
 * 阿里云短信服务
 */
public class AliSmsService {
    public String send(String message) {
        System.out.println("AliSmsService send message: " + message);
        return message;
    }
}
