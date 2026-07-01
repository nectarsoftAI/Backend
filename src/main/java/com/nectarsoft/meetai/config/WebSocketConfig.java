package com.nectarsoft.meetai.config;

import com.nectarsoft.meetai.controller.LiveWebSocketHandler;
import com.nectarsoft.meetai.controller.OnlineMeetingWebSocketHandler;
import com.nectarsoft.meetai.core.websocket.MeetingIdHandshakeInterceptor;
import com.nectarsoft.meetai.core.websocket.OnlineMeetingHandshakeInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired private LiveWebSocketHandler liveWebSocketHandler;
    @Autowired private OnlineMeetingWebSocketHandler onlineMeetingWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(liveWebSocketHandler, "/api/v1/live/ws/{meetingId}")
                .addInterceptors(new MeetingIdHandshakeInterceptor())
                .setAllowedOrigins("*");

        registry.addHandler(onlineMeetingWebSocketHandler, "/api/v1/online/ws/{meetingId}")
                .addInterceptors(new OnlineMeetingHandshakeInterceptor())
                .setAllowedOrigins("*");
    }
}
