package com.nectarsoft.meetai;

import com.nectarsoft.meetai.config.MeetAiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableConfigurationProperties(MeetAiProperties.class)
@EnableAsync
public class MeetAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(MeetAiApplication.class, args);
    }
}
