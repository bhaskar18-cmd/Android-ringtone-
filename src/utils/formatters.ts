import type { AudioStreamKey, RuleStreamsMap, VolumeSchedule } from '../types/volume';

export const STREAM_OPTIONS: Array<{
  key: AudioStreamKey;
  label: string;
  shortLabel: string;
  description: string;
}> = [
  {
    key: 'media',
    label: 'Media',
    shortLabel: 'Media',
    description: 'Music, video, and in-app media playback',
  },
  {
    key: 'ringtone',
    label: 'Ringtone',
    shortLabel: 'Ring',
    description: 'Incoming cellular calls and system ringer',
  },
  {
    key: 'notification',
    label: 'Notification',
    shortLabel: 'Notif',
    description: 'Messages, alerts, and app notifications',
  },
  {
    key: 'alarm',
    label: 'Alarm',
    shortLabel: 'Alarm',
    description: 'Clock alarms and timer alerts',
  },
  {
    key: 'voice_call',
    label: 'Voice Call',
    shortLabel: 'Call',
    description: 'Phone & VoIP call earpiece/speaker stream',
  },
];

export const WEEKDAY_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'] as const;

export function getStreamLabel(stream: string): string {
  return STREAM_OPTIONS.find((s) => s.key === stream)?.label ?? stream;
}

export function getStreamShortLabel(stream: string): string {
  return STREAM_OPTIONS.find((s) => s.key === stream)?.shortLabel ?? stream;
}

export function formatRuleStreamsSummary(streams: RuleStreamsMap): string {
  const parts = STREAM_OPTIONS.filter((opt) => streams[opt.key]?.enabled).map(
    (opt) => `${opt.shortLabel} ${streams[opt.key].percentage}%`
  );
  return parts.length > 0 ? parts.join(' · ') : 'No streams enabled';
}

export function formatTime12h(time24: string): string {
  const [hStr, mStr] = time24.split(':');
  const h = Number.parseInt(hStr || '0', 10);
  const m = Number.parseInt(mStr || '0', 10);
  if (Number.isNaN(h) || Number.isNaN(m)) return time24;
  const period = h >= 12 ? 'PM' : 'AM';
  const hour12 = h % 12 === 0 ? 12 : h % 12;
  return `${hour12}:${String(m).padStart(2, '0')} ${period}`;
}

export function formatDaysSummary(days: number[]): string {
  if (days.length === 7) return 'Every day';
  const sorted = [...days].sort((a, b) => a - b);
  if (sorted.join(',') === '1,2,3,4,5') return 'Weekdays';
  if (sorted.join(',') === '0,6') return 'Weekends';
  return sorted.map((d) => WEEKDAY_LABELS[d] ?? '').filter(Boolean).join(', ');
}

export function formatTriggerRelative(epochMs: number): string {
  if (!epochMs || epochMs <= 0) return 'Not queued';
  const diffMs = epochMs - Date.now();
  const date = new Date(epochMs);
  const timeStr = date.toLocaleTimeString([], {
    hour: 'numeric',
    minute: '2-digit',
  });
  const dayStr = date.toLocaleDateString([], {
    weekday: 'short',
  });

  if (diffMs <= 0) return `${dayStr} ${timeStr}`;
  const mins = Math.round(diffMs / 60000);
  if (mins < 60) return `In ${mins}m · ${timeStr}`;
  const hrs = Math.floor(mins / 60);
  const remMins = mins % 60;
  if (hrs < 24) return `In ${hrs}h ${remMins}m · ${dayStr} ${timeStr}`;
  return `${dayStr} · ${timeStr}`;
}

export function formatTimestamp(epochMs: number): string {
  if (!epochMs || epochMs <= 0) return 'Never';
  return new Date(epochMs).toLocaleString([], {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    second: '2-digit',
  });
}

export function inferActiveProfileName(schedules: VolumeSchedule[], schedulerEnabled: boolean): string {
  if (!schedulerEnabled) return 'Manual Control (Scheduler Paused)';
  const enabled = schedules.filter((s) => s.enabled);
  if (enabled.length === 0) return 'Manual Control (No Active Rules)';

  const now = new Date();
  const nowMinutes = now.getHours() * 60 + now.getMinutes();
  const today = now.getDay();

  const todayMatches = enabled
    .filter((s) => s.days.includes(today))
    .map((s) => {
      const [h, m] = s.time.split(':').map((n) => Number.parseInt(n, 10) || 0);
      return { schedule: s, mins: h * 60 + m };
    })
    .filter((item) => item.mins <= nowMinutes)
    .sort((a, b) => b.mins - a.mins);

  if (todayMatches.length > 0) {
    const s = todayMatches[0].schedule;
    const title = s.label || formatRuleStreamsSummary(s.streams);
    return `${title} · ${formatTime12h(s.time)}`;
  }

  const yesterday = (today + 6) % 7;
  const yesterdayMatches = enabled
    .filter((s) => s.days.includes(yesterday))
    .map((s) => {
      const [h, m] = s.time.split(':').map((n) => Number.parseInt(n, 10) || 0);
      return { schedule: s, mins: h * 60 + m };
    })
    .sort((a, b) => b.mins - a.mins);

  if (yesterdayMatches.length > 0) {
    const s = yesterdayMatches[0].schedule;
    const title = s.label || formatRuleStreamsSummary(s.streams);
    return `${title} · ${formatTime12h(s.time)} (Overnight)`;
  }

  return 'No active schedule';
}

export const PROFILE_ICON_OPTIONS: Array<{ key: string; label: string }> = [
  { key: 'sun', label: 'Day' },
  { key: 'moon', label: 'Night' },
  { key: 'briefcase', label: 'Work' },
  { key: 'home', label: 'Home' },
  { key: 'car', label: 'Drive' },
  { key: 'shield', label: 'Focus' },
  { key: 'bell', label: 'Alerts' },
  { key: 'volume', label: 'Media' },
];

export const PRIORITY_OPTIONS: Array<{ value: number; label: string }> = [
  { value: 0, label: 'Standard (0)' },
  { value: 1, label: 'Elevated (1)' },
  { value: 2, label: 'High (2)' },
  { value: 3, label: 'Critical (3)' },
];

export function toMinuteOfDay(time24: string): number {
  const [hStr, mStr] = time24.split(':');
  const h = Math.min(23, Math.max(0, Number.parseInt(hStr || '0', 10) || 0));
  const m = Math.min(59, Math.max(0, Number.parseInt(mStr || '0', 10) || 0));
  return h * 60 + m;
}

export function crossesMidnight(startTime: string, endTime: string): boolean {
  return toMinuteOfDay(startTime) > toMinuteOfDay(endTime);
}

export function isMinuteInProfileRange(
  minuteOfDay: number,
  startTime: string,
  endTime: string
): boolean {
  const startMin = toMinuteOfDay(startTime);
  const endMin = toMinuteOfDay(endTime);
  if (startMin <= endMin) {
    return minuteOfDay >= startMin && minuteOfDay <= endMin;
  }
  return minuteOfDay >= startMin || minuteOfDay <= endMin;
}

export function doProfileRangesOverlap(
  a: { startTime: string; endTime: string },
  b: { startTime: string; endTime: string }
): boolean {
  for (let m = 0; m < 1440; m++) {
    if (
      isMinuteInProfileRange(m, a.startTime, a.endTime) &&
      isMinuteInProfileRange(m, b.startTime, b.endTime)
    ) {
      return true;
    }
  }
  return false;
}

export function formatTimeRange12h(startTime: string, endTime: string): string {
  return `${formatTime12h(startTime)} – ${formatTime12h(endTime)}`;
}

