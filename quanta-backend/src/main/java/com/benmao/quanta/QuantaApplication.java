package com.benmao.quanta;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * quanta 后端启动类。
 *
 * <p>组件扫描根保持在 {@code com.benmao.quanta}，不放宽到 {@code com.benmao}，
 * 以免把桥接层的 {@code com.benmao.mt5} 一并扫入。
 */
@SpringBootApplication
@MapperScan("com.benmao.quanta.mapper")
public class QuantaApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuantaApplication.class, args);
    }
}