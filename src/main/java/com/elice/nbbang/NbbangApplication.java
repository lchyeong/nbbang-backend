package com.elice.nbbang;

import com.elice.nbbang.domain.payment.config.KakaoPayProperties;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.ulisesbocchio.jasyptspringboot.annotation.EnableEncryptableProperties;

@EnableJpaAuditing
@EnableConfigurationProperties(KakaoPayProperties.class)
@SpringBootApplication
@EnableEncryptableProperties
@EnableScheduling
public class NbbangApplication {

	public static void main(String[] args) {
//		환경변수를 복제하는게 아니라 참조만하는 것이기 때문에 해당 코드는 필요없음 로컬에선필요함...
//		Dotenv dotenv = Dotenv.load();
//		dotenv.entries().forEach(entry -> {
//			System.setProperty(entry.getKey(), entry.getValue());
//		});

		SpringApplication.run(NbbangApplication.class, args);
	}
}
