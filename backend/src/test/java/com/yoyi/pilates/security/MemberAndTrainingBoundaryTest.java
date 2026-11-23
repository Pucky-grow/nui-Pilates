package com.yoyi.pilates.security;

import com.yoyi.pilates.support.BookingFixture;
import com.yoyi.pilates.system.security.*;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.training.application.TrainingService;
import com.yoyi.pilates.training.interfaces.CoachTrainingController;
import com.yoyi.pilates.coach.application.CoachStudentService;
import com.yoyi.pilates.training.infrastructure.entity.TrainingRecordEntity;
import com.yoyi.pilates.user.infrastructure.entity.UserPrivacyEntity;
import com.yoyi.pilates.user.infrastructure.mapper.UserPrivacyMapper;
import com.yoyi.pilates.booking.interfaces.BookingController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MemberAndTrainingBoundaryTest extends BookingFixture {
    @Autowired TrainingService training;
    @Autowired CoachStudentService students;
    @Autowired CoachTrainingController controller;
    @Autowired BookingController bookingController;
    @Autowired UserPrivacyMapper privacyMapper;
    void login(String type,String role,Long id,Long coach){ LoginUser u=new LoginUser();u.setId(id);u.setType(type);u.setRoles(Set.of(role));u.setCoachId(coach);u.setPermissions(Set.of());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u,null,List.of())); }
    @Test void allBackofficeRolesWithSameIdCannotUseMemberIdentity(){
        bookings.book(userId,scheduleId,null,null);
        for(String role:List.of("ADMIN","STORE_MANAGER","STAFF","COACH")){login("ADMIN",role,userId,coachId);assertThrows(BusinessException.class,SecurityUtils::requireMemberId);assertThrows(BusinessException.class,()->bookingController.myBookings(null));}
        login("MP_USER","COACH",userId,coachId);assertEquals(userId,SecurityUtils.requireMemberId());assertDoesNotThrow(()->bookingController.myBookings(null));
    }
    @Test void trainingCannotCreateRelationshipOrSpoofBookingOwner(){
        Long other=newUser();TrainingRecordEntity fake=new TrainingRecordEntity();fake.setUserId(other);assertThrows(BusinessException.class,()->training.createRecord(coachId,false,fake));assertFalse(students.hasServed(coachId,other));
        Long id=bookings.book(userId,scheduleId,null,null);TrainingRecordEntity spoof=new TrainingRecordEntity();spoof.setBookingId(id);spoof.setUserId(other);assertThrows(BusinessException.class,()->training.createRecord(coachId,false,spoof));
        TrainingRecordEntity wrongCoach=new TrainingRecordEntity();wrongCoach.setBookingId(id);assertThrows(BusinessException.class,()->training.createRecord(coachId+1,false,wrongCoach));
    }
    @Test void directListsRespectPrivacy(){
        bookings.book(userId,scheduleId,null,null);UserPrivacyEntity p=new UserPrivacyEntity();p.setUserId(userId);p.setAllowAssessmentView(0);p.setAllowTrainingView(0);privacyMapper.insert(p);login("ADMIN","COACH",99L,coachId);
        assertThrows(BusinessException.class,()->controller.studentAssessments(userId));assertThrows(BusinessException.class,()->controller.studentRecords(userId,1,10));
        assertEquals(List.of(),students.studentDetail(coachId,userId).get("assessments"));
    }
}
