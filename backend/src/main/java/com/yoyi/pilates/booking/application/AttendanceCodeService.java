package com.yoyi.pilates.booking.application;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.QRCodeWriter;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.common.util.StudioTime;
import com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class AttendanceCodeService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PREFIX = "YOYI-CHECKIN:";
    private static final String KEY_PREFIX = "pilates:attendance:code:";
    private final StringRedisTemplate redis;

    public record Ticket(String code, String image, int expiresInSeconds) {}

    public Ticket issue(CourseScheduleEntity schedule) {
        LocalDateTime now = StudioTime.now();
        requireWindow(schedule, now);
        int seconds = (int) Math.min(300, Math.max(1, Duration.between(now,
                LocalDateTime.of(schedule.getCourseDate(), schedule.getEndTime())).toSeconds()));
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        String code = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String image = qrImage(code);
        redis.opsForValue().set(KEY_PREFIX + code, schedule.getId().toString(), Duration.ofSeconds(seconds));
        return new Ticket(code, image, seconds);
    }

    public Long requireScheduleId(String code) {
        if (code != null && code.matches("YOYI-CHECKIN:[A-Za-z0-9_-]{24}")) {
            String schedule = redis.opsForValue().get(KEY_PREFIX + code);
            if (schedule != null) return Long.valueOf(schedule);
        }
        throw BusinessException.of(ResultCode.BAD_REQUEST, "签到码无效或已过期，请扫描教练出示的新码");
    }

    static void requireWindow(CourseScheduleEntity schedule, LocalDateTime now) {
        if (schedule == null) throw BusinessException.of(ResultCode.SCHEDULE_NOT_FOUND);
        if (!"SCHEDULED".equals(schedule.getStatus()))
            throw BusinessException.of(ResultCode.CONFLICT, "排课已取消或结束，不能签到");
        if (now.isBefore(LocalDateTime.of(schedule.getCourseDate(), schedule.getStartTime()).minusMinutes(30)))
            throw BusinessException.of(ResultCode.CONFLICT, "开课前30分钟才可签到");
        if (!now.isBefore(LocalDateTime.of(schedule.getCourseDate(), schedule.getEndTime())))
            throw BusinessException.of(ResultCode.CONFLICT, "课程已结束，无法签到");
    }

    private String qrImage(String code) {
        try (var out = new ByteArrayOutputStream()) {
            var pixels = new QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, 240, 240);
            var image = new BufferedImage(pixels.getWidth(), pixels.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
            for (int y = 0; y < pixels.getHeight(); y++) {
                for (int x = 0; x < pixels.getWidth(); x++) {
                    image.setRGB(x, y, pixels.get(x, y) ? 0xff000000 : 0xffffffff);
                }
            }
            ImageIO.write(image, "PNG", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException | WriterException error) {
            throw new IllegalStateException("无法生成签到二维码", error);
        }
    }
}
