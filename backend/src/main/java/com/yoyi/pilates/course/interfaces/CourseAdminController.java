package com.yoyi.pilates.course.interfaces;

import com.yoyi.pilates.common.annotation.OpLog;
import com.yoyi.pilates.common.result.PageResult;
import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.course.application.CourseAdminService;
import com.yoyi.pilates.course.application.CourseQueryService;
import com.yoyi.pilates.course.infrastructure.entity.CoachEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseCategoryEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseEntity;
import com.yoyi.pilates.course.interfaces.dto.ClassroomSaveRequest;
import com.yoyi.pilates.course.interfaces.dto.CoachSaveRequest;
import com.yoyi.pilates.course.interfaces.dto.CourseSaveRequest;
import com.yoyi.pilates.system.security.RequiresPerm;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 课程/分类/教练/教室 后台管理接口.
 */
@RestController
@RequestMapping({"/admin", "/api/admin"})
@RequiredArgsConstructor
public class CourseAdminController {

    private final CourseAdminService courseAdminService;
    private final CourseQueryService courseQueryService;

    // ---------------- 课程 ----------------

    @GetMapping("/courses")
    @RequiresPerm("course:view")
    public Result<PageResult<CourseEntity>> pageCourses(@RequestParam(required = false) Long categoryId,
                                                        @RequestParam(required = false) String type,
                                                        @RequestParam(required = false) String keyword,
                                                        @RequestParam(defaultValue = "1") long page,
                                                        @RequestParam(defaultValue = "10") long size) {
        return Result.ok(PageResult.of(courseAdminService.pageCourses(categoryId, type, keyword, page, size), c -> c));
    }

    @GetMapping("/courses/{id}")
    @RequiresPerm("course:view")
    public Result<CourseEntity> detail(@PathVariable Long id) { return Result.ok(courseAdminService.courseDetail(id)); }

    @GetMapping("/classrooms")
    @RequiresPerm("classroom:manage")
    public Result<List<com.yoyi.pilates.course.infrastructure.entity.ClassroomEntity>> allRooms() { return Result.ok(courseAdminService.allRooms()); }

    @PostMapping("/courses")
    @RequiresPerm("course:create")
    @OpLog(module = "course", action = "create", detail = "#request.name")
    public Result<Long> createCourse(@Valid @RequestBody CourseSaveRequest request) {
        return Result.ok(courseAdminService.createCourse(request));
    }

    @PutMapping("/courses/{id}")
    @RequiresPerm("course:update")
    @OpLog(module = "course", action = "update", targetId = "#id")
    public Result<Void> updateCourse(@PathVariable Long id, @Valid @RequestBody CourseSaveRequest request) {
        courseAdminService.updateCourse(id, request);
        return Result.ok();
    }

    @PutMapping("/courses/{id}/shelf")
    @RequiresPerm("course:update")
    @OpLog(module = "course", action = "shelf", targetId = "#id", detail = "'onShelf=' + #onShelf")
    public Result<Void> toggleShelf(@PathVariable Long id, @RequestParam boolean onShelf) {
        courseAdminService.toggleCourseShelf(id, onShelf);
        return Result.ok();
    }

    @DeleteMapping("/courses/{id}")
    @RequiresPerm("course:delete")
    @OpLog(module = "course", action = "delete", targetId = "#id")
    public Result<Void> deleteCourse(@PathVariable Long id) {
        courseAdminService.deleteCourse(id);
        return Result.ok();
    }

    // ---------------- 分类 ----------------

    @GetMapping("/course-categories")
    @RequiresPerm("course:view")
    public Result<List<CourseCategoryEntity>> categories() {
        return Result.ok(courseQueryService.listActiveCategories());
    }

    @PostMapping("/course-categories")
    @RequiresPerm("course:create")
    @OpLog(module = "course", action = "category_create", detail = "#body.get('name')")
    public Result<Long> createCategory(@RequestBody Map<String, Object> body) {
        return Result.ok(courseAdminService.createCategory(
                (String) body.get("code"), (String) body.get("name"),
                body.get("sort") == null ? 0 : Integer.parseInt(body.get("sort").toString())));
    }

    @PutMapping("/course-categories/{id}")
    @RequiresPerm("course:update")
    @OpLog(module = "course", action = "category_update", targetId = "#id")
    public Result<Void> updateCategory(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        courseAdminService.updateCategory(id, (String) body.get("name"),
                body.get("sort") == null ? null : Integer.parseInt(body.get("sort").toString()),
                (String) body.get("status"));
        return Result.ok();
    }

    // ---------------- 教练 ----------------

    @GetMapping("/coaches")
    @RequiresPerm("coach:view")
    public Result<PageResult<CoachEntity>> pageCoaches(@RequestParam(required = false) String keyword,
                                                       @RequestParam(defaultValue = "1") long page,
                                                       @RequestParam(defaultValue = "10") long size) {
        return Result.ok(PageResult.of(courseAdminService.pageCoaches(keyword, page, size), c -> c));
    }

    @GetMapping("/coaches/{id}")
    @RequiresPerm("coach:view")
    public Result<CoachEntity> coachDetail(@PathVariable Long id) {
        CoachEntity coach=courseAdminService.coachDetail(id);coach.setOpenid(null);return Result.ok(coach);
    }

    @PostMapping("/coaches")
    @RequiresPerm("coach:manage")
    @OpLog(module = "coach", action = "create", detail = "#request.name")
    public Result<Long> createCoach(@Valid @RequestBody CoachSaveRequest request) {
        return Result.ok(courseAdminService.createCoach(request));
    }

    @PutMapping("/coaches/{id}")
    @RequiresPerm("coach:manage")
    @OpLog(module = "coach", action = "update", targetId = "#id")
    public Result<Void> updateCoach(@PathVariable Long id, @Valid @RequestBody CoachSaveRequest request) {
        courseAdminService.updateCoach(id, request);
        return Result.ok();
    }

    @DeleteMapping("/coaches/{id}")
    @RequiresPerm("coach:manage")
    @OpLog(module = "coach", action = "delete", targetId = "#id")
    public Result<Void> deleteCoach(@PathVariable Long id) {
        courseAdminService.deleteCoach(id);
        return Result.ok();
    }

    // ---------------- 教室 ----------------

    @PostMapping("/classrooms")
    @RequiresPerm("classroom:manage")
    @OpLog(module = "course", action = "classroom_create", detail = "#request.name")
    public Result<Long> createClassroom(@Valid @RequestBody ClassroomSaveRequest request) {
        return Result.ok(courseAdminService.createClassroom(request));
    }

    @PutMapping("/classrooms/{id}")
    @RequiresPerm("classroom:manage")
    @OpLog(module = "course", action = "classroom_update", targetId = "#id")
    public Result<Void> updateClassroom(@PathVariable Long id, @Valid @RequestBody ClassroomSaveRequest request) {
        courseAdminService.updateClassroom(id, request);
        return Result.ok();
    }
}
