const courseTypes = { GROUP: '团课', PRIVATE: '私教', EXPERIENCE: '体验课' };

function courseLabel(name, type) {
  return courseTypes[type] ? name + ' · ' + courseTypes[type] : name;
}

function endTime(start, durationMinutes) {
  if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(start)) return '';
  const duration = Number(durationMinutes);
  if (!Number.isInteger(duration) || duration <= 0) return '';
  const [hour, minute] = start.split(':').map(Number);
  const end = hour * 60 + minute + duration;
  if (end >= 24 * 60) return '';
  return String(Math.floor(end / 60)).padStart(2, '0') + ':' + String(end % 60).padStart(2, '0');
}

function futureStart(date, start, now = Date.now()) {
  return Date.parse(date + 'T' + start + ':00+08:00') > now;
}

module.exports = { courseLabel, endTime, futureStart };
