package com.yoyi.pilates.common.config;
import lombok.RequiredArgsConstructor;import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;import org.springframework.stereotype.Component;import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import com.yoyi.pilates.ledger.StudioLedgerAccess;
@Component @Profile({"prod","staging"}) @RequiredArgsConstructor
public class ProductionDatabaseGuard implements SmartInitializingSingleton {
 private final JdbcTemplate jdbc;
 private final org.springframework.core.env.Environment environment;
 @Override public void afterSingletonsInstantiated(){
  try {
   if(CloudrunPrivateMysqlPolicy.enabled(environment))CloudrunPrivateMysqlPolicy.verifyTarget(jdbc.queryForObject("SELECT DATABASE()",String.class),jdbc.queryForObject("SELECT @@server_uuid",String.class),jdbc.queryForObject("SELECT VERSION()",String.class));
   var history=jdbc.queryForList("SELECT version,state,checksum,applied_steps,total_steps FROM schema_history ORDER BY version");
   var names=history.stream().map(r->r.get("version").toString()).toList();
   var base=List.of("V001__structure.sql","V002__base.sql","V003__delivery.sql");
   var upgraded=new ArrayList<>(base);upgraded.add(StudioLedgerAccess.MIGRATION);
   boolean ledgerMigrated=names.equals(upgraded);
   if((!names.equals(base)&&!ledgerMigrated)
       || history.stream().anyMatch(r->!Set.of("DONE","BASELINED").contains(r.get("state").toString()))
       || (ledgerMigrated && !completeLedgerMigration(history.get(3)))
       || (environment.getProperty(StudioLedgerAccess.ENABLED_PROPERTY,Boolean.class,false)&&!ledgerMigrated)) {
    throw new IllegalStateException("schema version");
   }
   for(var row:jdbc.queryForList("SHOW GRANTS")){
    String grant=row.values().iterator().next().toString();var matcher=java.util.regex.Pattern.compile("^GRANT (.+?) ON (.+?) TO ",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(grant);
    if(!matcher.find())throw new IllegalStateException("inherited account grant requires review");
    for(String privilege:matcher.group(1).split(","))if(!Set.of("USAGE","SELECT","INSERT","UPDATE","DELETE").contains(privilege.trim().toUpperCase(Locale.ROOT)))throw new IllegalStateException("runtime account has excessive grants");
    if(grant.toUpperCase(Locale.ROOT).contains("WITH GRANT OPTION"))throw new IllegalStateException("grant option");
   }
   if(!"+08:00".equals(jdbc.queryForObject("SELECT @@session.time_zone",String.class)))throw new IllegalStateException("session timezone");
   Set<String> sqlMode=new HashSet<>(Arrays.asList(jdbc.queryForObject("SELECT @@session.sql_mode",String.class).split(",")));
   if(!sqlMode.contains("STRICT_TRANS_TABLES")&&!sqlMode.contains("STRICT_ALL_TABLES"))throw new IllegalStateException("strict SQL mode required");
   if(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sys_user' AND column_name='auth_version'",Integer.class)!=1)throw new IllegalStateException("account schema");
   if(!"FREE".equals(jdbc.queryForObject("SELECT config_value FROM system_config WHERE config_key='booking.paymentMode' AND deleted=0",String.class)))throw new IllegalStateException("FREE launch requires matching database rule");
  } catch(Exception error){throw new IllegalStateException("Production database readiness rejected: verify migration history, scoped grants, FREE booking rule and session timezone; values withheld.");}
 }
 private boolean completeLedgerMigration(Map<String,Object> row) {
  return "DONE".equals(row.get("state"))
      && StudioLedgerAccess.MIGRATION_SHA256.equals(row.get("checksum"))
      && ((Number)row.get("applied_steps")).intValue()==StudioLedgerAccess.MIGRATION_STEPS
      && ((Number)row.get("total_steps")).intValue()==StudioLedgerAccess.MIGRATION_STEPS
      && jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user' AND column_name='openid' AND is_nullable='YES'",Integer.class)==1;
 }

}
