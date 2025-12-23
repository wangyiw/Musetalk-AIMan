package com.gyq.config;

import jakarta.websocket.server.ServerEndpointConfig;
import org.springframework.context.ApplicationContext;

import org.springframework.stereotype.Component;

@Component
public class SpringConfigurator extends ServerEndpointConfig.Configurator {
    private static ApplicationContext context;

    public static void setApplicationContext(ApplicationContext applicationContext) {
        SpringConfigurator.context = applicationContext;
    }

    @Override
    public <T> T getEndpointInstance(Class<T> clazz) {
        return context.getBean(clazz);
    }
}
