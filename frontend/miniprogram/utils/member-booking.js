const { localDate, weekday } = require('./util');
const { money, cancellationNotice } = require('./studio-ledger');

const difficultyLabels = { BEGINNER: '入门', JUNIOR: '初级', INTERMEDIATE: '中级', ADVANCED: '进阶' };
const typeLabels = { GROUP: '小班课', PRIVATE: '私教课' };

function dateLabel(date, now = Date.now()) {
  if (date === localDate(0, now)) return '今天';
  if (date === localDate(1, now)) return '明天';
  return date ? date.slice(5, 10).replace('-', '/') + ' ' + weekday(date) : '';
}

function studioTimestamp(date, time) {
  return date && time ? Date.parse(date + 'T' + time.slice(0, 5) + ':00+08:00') : NaN;
}

function bookingGroups(items, now = Date.now()) {
  const upcoming = [], history = [];
  for (const booking of items) {
    const end = studioTimestamp(booking.courseDate, booking.endTime || booking.startTime);
    const pending = booking.status === 'BOOKED' || booking.status === 'SIGNED_IN';
    (pending && (end > now || booking.canCheckin) ? upcoming : history).push(booking);
  }
  const key = booking => booking.courseDate ? booking.courseDate + 'T' + (booking.startTime || '') : booking.createdAt || '';
  return {
    upcoming: upcoming.sort((a, b) => key(a).localeCompare(key(b))),
    history: history.sort((a, b) => key(b).localeCompare(key(a)))
  };
}

function checkinHint(booking, now = Date.now()) {
  if (booking.status !== 'BOOKED') return '';
  if (booking.canCheckin) return '现在可以签到';
  const start = studioTimestamp(booking.courseDate, booking.startTime);
  if (!Number.isFinite(start)) return '';
  const open = start - 30 * 60000;
  if (open > now) {
    const openedAt = new Date(open + 8 * 3600000).toISOString();
    const date = openedAt.slice(0, 10);
    const day = date === booking.courseDate ? '' : dateLabel(date, now) + ' ';
    return day + openedAt.slice(11, 16) + ' 开始签到';
  }
  const end = studioTimestamp(booking.courseDate, booking.endTime);
  return end <= now ? '签到已结束' : '请刷新查看签到状态';
}

function feeHint(booking) {
  const terms = booking.cancellationTerms;
  if (!terms?.lateFeeFen) return '';
  if (booking.status === 'BOOKED' && terms.state === 'BOOKED') return cancellationNotice(booking).trim();
  const amount = '¥' + money(terms.lateFeeFen);
  return {
    ELIGIBLE: booking.publicCancelReason ? '' : '取消费待门店确认，暂未扣款。',
    WAITING_BALANCE: '取消费待扣 ' + amount,
    POSTED: '已扣取消费 ' + amount,
    WAIVED: '取消费已免收',
    REVERSED: '取消费已退回余额',
    TIMELY: '未收取取消费'
  }[terms.state] || '';
}

function bookingDisplay(booking) {
  return {
    ...booking,
    dayText: dateLabel(booking.courseDate),
    timeText: [booking.startTime, booking.endTime].filter(Boolean).map(time => time.slice(0, 5)).join(' – '),
    checkinText: checkinHint(booking),
    feeText: feeHint(booking)
  };
}

function scheduleDisplay(schedule) {
  return {
    ...schedule,
    dayText: dateLabel(schedule.courseDate),
    timeText: [schedule.startTime, schedule.endTime].filter(Boolean).map(time => time.slice(0, 5)).join(' – '),
    difficultyText: difficultyLabels[schedule.difficulty] || '',
    typeText: typeLabels[schedule.courseType] || ''
  };
}

module.exports = { bookingGroups, bookingDisplay, scheduleDisplay, difficultyLabels, typeLabels };
