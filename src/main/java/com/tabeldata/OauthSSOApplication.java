package com.tabeldata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableTransactionManagement
public class OauthSSOApplication {

	public static void main(String[] args) {
		SpringApplication.run(OauthSSOApplication.class, args);
	}
}
