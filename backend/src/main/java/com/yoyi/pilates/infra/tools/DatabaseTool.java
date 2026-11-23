package com.yoyi.pilates.infra.tools;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import com.yoyi.pilates.system.application.AccountService;

/** Offline migrator/bootstrap: protected config, pinned server identity and a session advisory lock. */
public final class DatabaseTool {
 public static void main(String[] args) throws Exception {
  try { run(args); }
  catch(Exception error) {System.err.println("STOP: "+error.getClass().getSimpleName()+"; no credentials or SQL payload emitted. Inspect schema history and documented recovery steps.");System.exit(1);}
 }
 static void run(String[] args) throws Exception {
  Map<String,String> opts=new HashMap<>();for(int i=0;i<args.length;i+=2)opts.put(args[i],args[i+1]);
  Path cfg=Path.of(Objects.requireNonNull(opts.get("--config"))).toAbsolutePath();
  if(!Files.isRegularFile(cfg)||Files.isSymbolicLink(cfg))throw new IllegalArgumentException("config must be a protected regular file");
  var permissions=Files.getPosixFilePermissions(cfg);
  if(permissions.stream().anyMatch(p->p.name().startsWith("GROUP_")||p.name().startsWith("OTHERS_")))throw new IllegalArgumentException("config must be 0600/0400");
  Properties settings=new Properties();try(var input=Files.newInputStream(cfg)){settings.load(input);}
  String url=settings.getProperty("url"), database=settings.getProperty("database"), expected=opts.get("--expect-server-uuid");
  if(database==null||!database.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}")||expected==null||!url.startsWith("jdbc:mysql:"))throw new IllegalArgumentException("explicit MySQL database and UUID are required");
  try(Connection c=DriverManager.getConnection(url,settings.getProperty("user"),settings.getProperty("password"))) {
   if(!expected.equals(one(c,"SELECT @@server_uuid"))||!database.equals(one(c,"SELECT DATABASE()")))throw new IllegalStateException("wrong target");
   if(!"1".equals(one(c,"SELECT GET_LOCK('pilates-schema-"+database+"',0)")))throw new IllegalStateException("migration already running");
   String mode=opts.get("--mode");
   if("provision".equals(mode)){provision(c,settings,database);return;}
   if("bootstrap".equals(mode)){bootstrap(c,settings);return;}
   Path directory=Path.of(opts.getOrDefault("--directory","database/production"));
   boolean tracked="1".equals(one(c,"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='schema_history'"));
   int existing=Integer.parseInt(one(c,"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"));
   if(!tracked&&existing>0&& !"adopt-round3".equals(mode))throw new IllegalStateException("nonempty unversioned database; review adoption first");
   if(!tracked&&existing>0){precheck(c);verifyRound3Shape(c,Path.of(opts.getOrDefault("--directory","database/production")).resolve("V001__structure.sql"));}
   execute(c,"CREATE TABLE IF NOT EXISTS schema_history(version VARCHAR(100) PRIMARY KEY,checksum CHAR(64) NOT NULL,state VARCHAR(20) NOT NULL,applied_steps INT NOT NULL DEFAULT 0,total_steps INT NOT NULL,updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");
   try(var files=Files.list(directory)) {
    for(Path path:files.filter(p->p.getFileName().toString().matches("V\\d+__.*\\.sql")).sorted().toList()) {
     String name=path.getFileName().toString(), sql=Files.readString(path), checksum=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8)));
     List<String> statements=split(sql);int step=0;String state=null;
     try(var q=c.prepareStatement("SELECT checksum,state,applied_steps FROM schema_history WHERE version=?")) {q.setString(1,name);try(var r=q.executeQuery()){if(r.next()){if(!checksum.equals(r.getString(1)))throw new IllegalStateException("migration checksum changed");state=r.getString(2);step=r.getInt(3);}}}
     if("DONE".equals(state)||"BASELINED".equals(state)){System.out.println("SKIP "+name);continue;}
     if(state!=null&& !"resume-verified".equals(mode))throw new IllegalStateException("dirty migration; verify completed steps and explicit resume");
     if(!tracked&&existing>0&&(name.startsWith("V001")||name.startsWith("V002"))){history(c,name,checksum,"BASELINED",statements.size(),statements.size());System.out.println("BASELINED "+name);continue;}
     if(name.startsWith("V003"))precheckNotifications(c);
     history(c,name,checksum,"DIRTY",step,statements.size());
     boolean dml=name.startsWith("V002");if(dml)c.setAutoCommit(false);
     try {
      for(int i=step;i<statements.size();i++) {
       if(name.equals(opts.getOrDefault("--fail-version",name))&&i==Integer.parseInt(opts.getOrDefault("--fail-before-step","-1")))throw new IllegalStateException("injected interruption");
       execute(c,statements.get(i));history(c,name,checksum,"DIRTY",i+1,statements.size());
      }
      history(c,name,checksum,"DONE",statements.size(),statements.size());if(dml)c.commit();
     }catch(Exception error){if(dml)c.rollback();throw error;}finally{if(dml)c.setAutoCommit(true);}
     System.out.println("APPLIED "+name);
    }
   }finally{one(c,"SELECT RELEASE_LOCK('pilates-schema-"+database+"')");}
  }
 }
 static void verifyRound3Shape(Connection c,Path schema)throws Exception {
  if(!"0".equals(one(c,"SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sys_user' AND column_name='auth_version'")))throw new IllegalStateException("untracked delivery extension requires manual review");
  for(String statement:split(Files.readString(schema))){
   var table=java.util.regex.Pattern.compile("(?is)^CREATE TABLE `?([a-zA-Z0-9_]+)`?\\s*\\(").matcher(statement);
   if(!table.find())continue;
   String name=table.group(1);
   var columns=new HashSet<String>();try(var query=c.prepareStatement("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=?")){query.setString(1,name);try(var rows=query.executeQuery()){while(rows.next())columns.add(rows.getString(1));}}
   var expected=java.util.regex.Pattern.compile("(?m)^\\s*`([a-zA-Z0-9_]+)`\\s").matcher(statement);
   while(expected.find())if(!columns.contains(expected.group(1)))throw new IllegalStateException("unrecognized Round3 table/columns");
  }
 }
 static void precheck(Connection c)throws SQLException {
  if(!"1".equals(one(c,"SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='booking' AND index_name='uk_active_user_schedule'")))throw new IllegalStateException("Round2 migration required first");
  if(!"0".equals(one(c,"SELECT COUNT(*) FROM course_schedule s WHERE s.deleted=0 AND (s.booked_count>s.max_capacity OR s.booked_count<>(SELECT COUNT(*) FROM booking b WHERE b.schedule_id=s.id AND b.deleted=0 AND b.status IN ('BOOKED','SIGNED_IN','COMPLETED','NO_SHOW')))")))throw new IllegalStateException("booking counts require review");
  precheckNotifications(c);
 }
 static void precheckNotifications(Connection c)throws SQLException {
  if(!"0".equals(one(c,"SELECT COUNT(*) FROM (SELECT user_id,biz_type,biz_id,deleted FROM notification WHERE biz_id IS NOT NULL AND biz_type IS NOT NULL GROUP BY user_id,biz_type,biz_id,deleted HAVING COUNT(*)>1) duplicates")))throw new IllegalStateException("duplicate notifications require review");
 }
 static void provision(Connection c,Properties settings,String database)throws SQLException {
  for(String kind:List.of("runtime","migrator")){
   String user=settings.getProperty(kind+".username"),password=settings.getProperty(kind+".password"),host=settings.getProperty(kind+".host");
   if(user==null||!user.matches("[a-zA-Z][a-zA-Z0-9_]{2,31}")||password==null||!password.matches("[a-fA-F0-9]{48,128}")||host==null||!host.matches("[a-zA-Z0-9%_.:-]+"))throw new IllegalArgumentException("protected provisioning settings invalid");
   String account="'"+user+"'@'"+host+"'";
   execute(c,"CREATE USER "+account+" IDENTIFIED BY '"+password+"'");
   if(kind.equals("migrator"))execute(c,"GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,DROP,REFERENCES ON `"+database+"`.* TO "+account);
   else {
    List<String> tables=new ArrayList<>();try(var query=c.createStatement();var rows=query.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'")){while(rows.next())tables.add(rows.getString(1));}
    for(String table:tables){if(!table.matches("[a-zA-Z0-9_]+"))throw new IllegalStateException("unexpected table");execute(c,"GRANT "+String.join(",", runtimePrivileges(table))+" ON `"+database+"`.`"+table+"` TO "+account);}
   }
  }System.out.println("Provisioned scoped accounts. No credentials emitted.");
 }
 public static Set<String> runtimePrivileges(String table) {
  return switch (table) {
   case "schema_history" -> Set.of("SELECT");
   case "studio_wallet_entry", "studio_admin_audit" -> Set.of("SELECT", "INSERT");
   default -> Set.of("SELECT", "INSERT", "UPDATE", "DELETE");
  };
 }
 static void bootstrap(Connection c,Properties p)throws Exception {
  c.setAutoCommit(false);
  if(!"0".equals(one(c,"SELECT COUNT(*) FROM sys_user")))throw new IllegalStateException("bootstrap requires an empty account table");
  String username=p.getProperty("bootstrap.username"), name=p.getProperty("bootstrap.name");
  if(username==null||!username.matches("[A-Za-z0-9_.-]{3,32}")||name==null||name.isBlank()||name.length()>32)throw new IllegalArgumentException("bootstrap identity required");
  System.err.println("Initial administrator password (stdin; never a command argument):");
  char[] secret=System.console()!=null?System.console().readPassword():new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8)).readLine().toCharArray();
  String raw=new String(secret);AccountService.validatePassword(raw);String hash=new BCryptPasswordEncoder(12).encode(raw);Arrays.fill(secret,'\0');raw=null;
  try(var q=c.prepareStatement("INSERT INTO sys_user(username,name,password,status,auth_version) VALUES(?,?,?,'ACTIVE',0)",Statement.RETURN_GENERATED_KEYS)) {
   q.setString(1,username);q.setString(2,name);q.setString(3,hash);q.executeUpdate();try(var keys=q.getGeneratedKeys()){keys.next();long id=keys.getLong(1);
    try(var grant=c.prepareStatement("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE code='ADMIN' AND status='ACTIVE' AND deleted=0")){grant.setLong(1,id);if(grant.executeUpdate()!=1)throw new IllegalStateException("ADMIN role missing/inactive");}
   }
  }c.commit();System.out.println("Administrator initialized; no password emitted.");
 }
 static void history(Connection c,String name,String checksum,String state,int done,int total)throws SQLException {
  try(var q=c.prepareStatement("INSERT INTO schema_history(version,checksum,state,applied_steps,total_steps) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE state=VALUES(state),applied_steps=VALUES(applied_steps)")){q.setString(1,name);q.setString(2,checksum);q.setString(3,state);q.setInt(4,done);q.setInt(5,total);q.executeUpdate();}
 }
 static void execute(Connection c,String sql)throws SQLException{try(var s=c.createStatement()){s.execute(sql);}}
 static String one(Connection c,String sql)throws SQLException{try(var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getString(1);}}
 /** SQL splitter supports escaped/doubled quotes and line/block comments used by this repository. */
 public static List<String> split(String source){
  List<String> result=new ArrayList<>();StringBuilder line=new StringBuilder();char quote=0;
  for(int i=0;i<source.length();i++){char ch=source.charAt(i),next=i+1<source.length()?source.charAt(i+1):0;
   if(quote==0&&ch=='-'&&next=='-'){while(i<source.length()&&source.charAt(i)!='\n')i++;line.append('\n');continue;}
   if(quote==0&&ch=='/'&&next=='*'){i+=2;while(i+1<source.length()&&!(source.charAt(i)=='*'&&source.charAt(i+1)=='/'))i++;i++;line.append(' ');continue;}
   if(quote!=0&&ch=='\\'&&next!=0){line.append(ch).append(next);i++;continue;}
   if(ch=='\''||ch=='"'||ch=='`'){if(quote==0)quote=ch;else if(quote==ch){if(next==ch){line.append(ch).append(next);i++;continue;}quote=0;}}
   if(ch==';'&&quote==0){if(!line.toString().isBlank())result.add(line.toString().trim());line.setLength(0);}else line.append(ch);
  }if(quote!=0)throw new IllegalArgumentException("unterminated SQL quote");if(!line.toString().isBlank())result.add(line.toString().trim());return result;
 }
}
