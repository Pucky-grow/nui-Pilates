package com.yoyi.pilates.home;

import com.yoyi.pilates.home.infrastructure.mapper.HomeHotCourseMapper;
import com.yoyi.pilates.home.infrastructure.mapper.HomeTeamCoachMapper;
import java.sql.*;
import java.util.*;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HomePlacementSqlTest {
    @Test void repeatedSaveClearAndReselectReuseUniqueRows() throws Exception {
        for (var mapper : List.of(HomeHotCourseMapper.class, HomeTeamCoachMapper.class)) {
            boolean hot = mapper == HomeHotCourseMapper.class;
            String table = hot ? "home_hot_course" : "home_team_coach";
            String key = hot ? "course_id" : "coach_id";
            String property = hot ? "courseId" : "coachId";
            var method = Arrays.stream(mapper.getMethods()).filter(m -> m.getName().equals("savePlacement")).findFirst().orElseThrow();
            String original = method.getAnnotation(Insert.class).value()[0];
            var names = new ArrayList<String>();
            var matches = java.util.regex.Pattern.compile("#\\{(\\w+)\\}").matcher(original);
            while (matches.find()) names.add(matches.group(1));
            String sql = original.replaceAll("#\\{\\w+\\}", "?");
            try (var db = DriverManager.getConnection("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL", "sa", "")) {
                db.createStatement().execute("CREATE TABLE "+table+" (id BIGINT AUTO_INCREMENT PRIMARY KEY, "+key+" BIGINT UNIQUE NOT NULL, sort INT, enabled INT, intro VARCHAR(255), deleted INT DEFAULT 0, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
                for (int cycle=0; cycle<3; cycle++) {
                    db.createStatement().executeUpdate("UPDATE "+table+" SET deleted=1 WHERE deleted=0");
                    try (var statement = db.prepareStatement(sql)) {
                        Map<String,Object> values = Map.of(property,7L,"sort",cycle,"enabled",1,"intro","latest-"+cycle);
                        for (int i=0;i<names.size();i++) statement.setObject(i+1,values.get(names.get(i)));
                        statement.executeUpdate();
                    }
                    try (var rows=db.createStatement().executeQuery("SELECT COUNT(*) FROM "+table+" WHERE deleted=0 AND sort="+cycle)) { rows.next(); assertEquals(1,rows.getInt(1)); }
                    try (var rows=db.createStatement().executeQuery("SELECT COUNT(*) FROM "+table)) { rows.next(); assertEquals(1,rows.getInt(1)); }
                    if(!hot)try(var rows=db.createStatement().executeQuery("SELECT intro FROM "+table)){ rows.next();assertEquals("latest-"+cycle,rows.getString(1)); }
                }
            }
        }
    }
}
