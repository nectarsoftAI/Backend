package com.nectarsoft.meetai.config;

import com.nectarsoft.meetai.controller.LiveWebSocketHandler;
import com.nectarsoft.meetai.core.websocket.MeetingIdHandshakeInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private LiveWebSocketHandler liveWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(liveWebSocketHandler, "/api/v1/live/ws/{meetingId}")
                .addInterceptors(new MeetingIdHandshakeInterceptor())
                .setAllowedOrigins("*");
    }
}
