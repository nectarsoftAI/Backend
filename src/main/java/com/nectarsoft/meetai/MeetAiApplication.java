package com.nectarsoft.meetai;

import com.nectarsoft.meetai.controller.LiveWebSocketHandler;
import com.nectarsoft.meetai.core.websocket.WebSocketManager;
import com.nectarsoft.meetai.service.LiveService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.nectarsoft.meetai.config.MeetAiProperties;

@SpringBootApplication
@EnableConfigurationProperties(MeetAiProperties.class)
@EnableAsync
public class MeetAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(MeetAiApplication.class, args);
    }

    @Component
    static class WsInitializer {
        @Autowired WebSocketManager wsManager;
        @Autowired LiveService liveService;

        @EventListener(ApplicationReadyEvent.class)
        public void onReady() {
            LiveWebSocketHandler.init(wsManager, liveService);
        }
    }
}
