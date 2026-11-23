package com.yoyi.pilates.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Full current schema + init_data only; no booking upgrade or fixture resets in this database. */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("junit")
@EnabledIfEnvironmentVariable(named="PILATES_TEST_MYSQL_FRESH_URL",matches=".+")
class Round3FreshDatabaseTest {
    @DynamicPropertySource static void ownedFreshDatabase(DynamicPropertyRegistry registry) {
        String url=System.getenv("PILATES_TEST_MYSQL_FRESH_URL");
        if(url==null||!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/yoyi_pilates_round3_[a-f0-9]+_fresh\\?.*"))throw new IllegalStateException("Owned fresh database only");
        registry.add("spring.datasource.url",()->url);
    }
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Test void currentApplicationStartsAndRegistersUnpackagedMemberOnSchemaAndInitOnly() {
        assertEquals("8.0.46",jdbc.queryForObject("SELECT VERSION()",String.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='booking' AND index_name='uk_active_user_schedule'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='booking' AND index_name='uk_user_schedule'",Integer.class));
        var rules=http.getForObject("/api/booking-rules",JsonNode.class);assertEquals("FREE",rules.path("data").path("bookingMode").asText());
        var login=http.postForObject("/api/auth/wx-login",Map.of("code","round3_fresh_isolated_member"),JsonNode.class);assertEquals(0,login.path("code").asInt());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM member",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_package",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM `order`",Integer.class));
    }
}
