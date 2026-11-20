package com.semantic.platform.distribution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 启动引导：样例数据播种后，若尚无编译版本则执行一次全量编译，
 * 使分布查询开箱即用（观察值 + 近似摘要就绪）。
 */
@Component
@Order(10) // 在 DataSeeder 之后运行
public class DistBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DistBootstrap.class);

    private final DistService distService;
    private final DistCompileRepository compileRepo;

    public DistBootstrap(DistService distService, DistCompileRepository compileRepo) {
        this.distService = distService;
        this.compileRepo = compileRepo;
    }

    @Override
    public void run(String... args) {
        if (compileRepo.count() > 0) {
            log.info("已存在编译版本，跳过启动全量编译");
            return;
        }
        log.info("首次启动：执行订单金额全量编译...");
        Map<String, Object> result = distService.compile("full", "bootstrap", null);
        log.info("全量编译完成: {}", result);
    }
}
