package com.yoyi.pilates.production;

import com.yoyi.pilates.common.config.CloudrunPrivateMysqlPolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CloudrunPrivateMysqlPolicyTest {
    static final String URL = "jdbc:mysql://10.39.100.38:3306/uni_pilates_test?sslMode=DISABLED&connectionTimeZone=Asia/Shanghai&connectTimeout=5000&socketTimeout=10000";
    void validate(String profile, String url, String user) {
        CloudrunPrivateMysqlPolicy.validateSettings(profile, CloudrunPrivateMysqlPolicy.ENVIRONMENT,
                CloudrunPrivateMysqlPolicy.ACKNOWLEDGEMENT, url, user);
    }
    @Test void privateExceptionRequiresExactStagingAndAcknowledgement() {
        validate("staging", URL, "uni_pilates_runtime");
        for (String profile : new String[]{"prod", "dev", "staging,dev"}) assertThrows(IllegalStateException.class, () -> validate(profile, URL, "uni_pilates_runtime"));
        assertThrows(IllegalStateException.class, () -> CloudrunPrivateMysqlPolicy.validateSettings("staging", CloudrunPrivateMysqlPolicy.ENVIRONMENT, "", URL, "uni_pilates_runtime"));
    }
    @Test void rejectsPublicWrongAndAmbiguousTargetsBeforeConnection() {
        for (String url : new String[]{URL.replace("10.39.100.38", "203.0.113.10"), URL.replace("10.39.100.38", "10.60.1.20"), URL.replace("uni_pilates_test", "business"), URL + "&allowPublicKeyRetrieval=true", URL + "&sslMode=DISABLED", URL.replace("DISABLED", "PREFERRED"), URL.replace("10000", "0"), URL.replace("10.39.100.38", "user:password@10.39.100.38")}) {
            assertThrows(IllegalStateException.class, () -> validate("staging", url, "uni_pilates_runtime"));
        }
        assertThrows(IllegalStateException.class, () -> validate("staging", URL, "root"));
    }
    @Test void databaseUuidAndVersionRemainPinned() {
        CloudrunPrivateMysqlPolicy.verifyTarget(CloudrunPrivateMysqlPolicy.DATABASE, CloudrunPrivateMysqlPolicy.UUID, CloudrunPrivateMysqlPolicy.VERSION);
        assertThrows(IllegalStateException.class, () -> CloudrunPrivateMysqlPolicy.verifyTarget(CloudrunPrivateMysqlPolicy.DATABASE, "00000000-0000-0000-0000-000000000000", CloudrunPrivateMysqlPolicy.VERSION));
        assertThrows(IllegalStateException.class, () -> CloudrunPrivateMysqlPolicy.verifyTarget(CloudrunPrivateMysqlPolicy.DATABASE, CloudrunPrivateMysqlPolicy.UUID, "8.0.46"));
    }
}
