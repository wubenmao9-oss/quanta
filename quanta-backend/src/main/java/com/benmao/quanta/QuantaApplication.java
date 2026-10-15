package com.benmao.quanta;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * quanta 后端启动类。
 *
 * <p>组件扫描根保持在 {@code com.benmao.quanta}，不放宽到 {@code com.benmao}，
 * 以免把桥接层的 {@code com.benmao.mt5} 一并扫入。
 *
 * <p>{@code @MapperScan} 显式登记两个包：{@code mapper}（通用表）与 {@code cycle}（实盘轮相关表，
 * 其 Mapper 与状态机同包，便于内聚；该包内其余类均为普通 Bean，不受影响）。
 * 注意：一旦显式声明 {@code @MapperScan}，MyBatis 的自动扫描会退避，未登记的包不会被注册。
 */
@SpringBootApplication
@MapperScan({"com.benmao.quanta.mapper", "com.benmao.quanta.cycle"})
public class QuantaApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuantaApplication.class, args);
    }
}