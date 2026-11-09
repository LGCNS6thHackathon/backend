package kr.wsts.lms;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:lms-context-smoke;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.bootstrap.admin-email=","app.bootstrap.admin-password="})
class BackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
