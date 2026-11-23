package com.yoyi.pilates.ledger;

import com.yoyi.pilates.common.util.StudioTime;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Runs inside the member's existing booking cancellation transaction. */
@Component
@RequiredArgsConstructor
public class StudioCancellationCapture {
    private final StudioLedgerAccess access;
    private final LedgerStore db;

    public record MemberTerms(LocalDateTime cutoffAt, long lateFeeFen, boolean lateNow, String state) {}

    public boolean hasTerms(long bookingId) {
        return access.enabled() && db.find("SELECT booking_id FROM studio_booking_terms WHERE booking_id=?", bookingId) != null;
    }

    public Map<Long, MemberTerms> termsForUser(long userId) {
        if (!access.enabled()) return Map.of();
        var now = StudioTime.now();
        return db.list("""
                SELECT t.booking_id,t.cutoff_at,t.start_at,t.late_fen,t.state
                FROM studio_booking_terms t JOIN member m ON m.id=t.member_id AND m.deleted=0
                WHERE m.user_id=? AND t.late_fen>0
                """, userId).stream().collect(Collectors.toMap(row -> row.number("booking_id"), row ->
                new MemberTerms(row.time("cutoff_at"), row.number("late_fen"),
                        now.isAfter(row.time("cutoff_at")) && now.isBefore(row.time("start_at")), row.text("state"))));
    }

    public void memberCancelled(long bookingId, LocalDateTime cancelledAt) {
        if (!access.enabled()) return;
        db.update("""
                UPDATE studio_booking_terms SET cancelled_at=?,state=CASE
                    WHEN late_fen>0 AND ? > cutoff_at AND ? < start_at THEN 'ELIGIBLE' ELSE 'TIMELY' END
                WHERE booking_id=? AND state='BOOKED'
                """, cancelledAt, cancelledAt, cancelledAt, bookingId);
    }
}
