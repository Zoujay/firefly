package firefly;

import com.zaxxer.hikari.HikariDataSource;
import firefly.support.FireflyIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@FireflyIntegrationTest
class FireflyApplicationTests {

    @Autowired
    private DataSource dataSource;

    @Test
    void contextLoads() {
        HikariDataSource hikariDataSource =
                assertInstanceOf(HikariDataSource.class, dataSource);
        assertEquals(2, hikariDataSource.getMinimumIdle());
        assertEquals(30, hikariDataSource.getMaximumPoolSize());
        assertEquals(30_000, hikariDataSource.getConnectionTimeout());
        assertEquals(3_000, hikariDataSource.getValidationTimeout());
        assertEquals(300_000, hikariDataSource.getIdleTimeout());
    }

}
