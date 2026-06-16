package com.ashoo;

import org.springframework.boot.SpringApplication;

public class TestAshooApplication {

	public static void main(String[] args) {
		SpringApplication.from(AshooApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
