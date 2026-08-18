package firefly.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import firefly.support.FireflyIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@FireflyIntegrationTest
class GitHubSchemaIntegrationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsGitHubTablesWithoutForeignKeysAndKeepsUniqueGuards() {
        Integer tableCount =
            jdbcTemplate.queryForObject(
                """
                    select count(*)
                      from information_schema.tables
                     where table_schema = database()
                       and table_name in (
                         'github_connection',
                         'github_repository_subscription',
                         'github_trigger_config',
                         'github_webhook_delivery',
                         'github_delivery_pipeline'
                       )
                    """,
                Integer.class);
        Integer foreignKeyCount =
            jdbcTemplate.queryForObject(
                """
                    select count(*)
                      from information_schema.table_constraints
                     where constraint_schema = database()
                       and table_name like 'github%'
                       and constraint_type = 'FOREIGN KEY'
                    """,
                Integer.class);
        Integer uniqueCount =
            jdbcTemplate.queryForObject(
                """
                    select count(distinct index_name)
                      from information_schema.statistics
                     where table_schema = database()
                       and table_name like 'github%'
                       and non_unique = 0
                       and index_name <> 'PRIMARY'
                    """,
                Integer.class);

        assertEquals(5, tableCount);
        assertEquals(0, foreignKeyCount);
        assertTrue(uniqueCount >= 7);
    }
}
