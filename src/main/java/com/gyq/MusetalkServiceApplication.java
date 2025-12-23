package com.gyq;

import com.gyq.config.SpringConfigurator;
import org.springframework.beans.BeansException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;

@SpringBootApplication
public class MusetalkServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(MusetalkServiceApplication.class, args);
	}

	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		SpringConfigurator.setApplicationContext(applicationContext);
	}
}
