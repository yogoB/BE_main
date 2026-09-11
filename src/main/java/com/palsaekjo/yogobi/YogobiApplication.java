package com.palsaekjo.yogobi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling  // 개인정보 보유기간 자동 파기(RetentionService)
public class YogobiApplication {
    public static void main(String[] args) {
        SpringApplication.run(YogobiApplication.class, args);
    }
}
