package com.fams;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

import com.fams.shared.time.VietnamTime;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class ApiServerApplication {

	public static void main(String[] args) {
		// Business dates and shift times are Vietnamese local time. Absolute timestamps remain
		// instants in PostgreSQL; this default only protects legacy LocalDate.now() call sites.
		TimeZone.setDefault(TimeZone.getTimeZone(VietnamTime.ZONE));
		SpringApplication.run(ApiServerApplication.class, args);
	}

}
