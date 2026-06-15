package com.nectarsoft.meetai.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI meetAiOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("MeetAI API")
                        .description("AI 회의록 자동 생성 시스템 — 넥타르소프트")
                        .version("1.0.0"));
    }
}
