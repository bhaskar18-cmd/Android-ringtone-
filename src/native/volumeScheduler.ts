import { Capacitor, registerPlugin } from '@capacitor/core';
import type {
  AudioModeProbeResponse,
  AudioStreamKey,
  DeleteScheduledChangeResponse,
  DeleteVolumeProfileResponse,
  ExecutionLogEntry,
  GetScheduledChangesResponse,
  GetVolumeProfilesResponse,
  PermissionActionType,
  PermissionStatusResponse,
  ProfileOverlapReport,
  ProfileResolution,
  RequestPermissionResponse,
  RuleStreamsMap,
  SaveVolumeProfileParams,
  SaveVolumeProfileResponse,
  ScheduleVolumeChangeParams,
  ScheduleVolumeChangeResponse,
  SchedulerToggleResponse,
  SetDefaultProfileResponse,
  StreamSnapshot,
  StreamTestResult,
  TestAlarmResponse,
  ToggleProfileResponse,
  VolumeApplyResult,
  VolumeProfile,
  VolumeSchedule,
  VolumeSchedulerPluginInterface,
  VolumeSettingsResponse,
} from '../types/volume';
import {
  doProfileRangesOverlap,
  isMinuteInProfileRange,
  toMinuteOfDay,
} from '../utils/formatters';

const NativeVolumeScheduler = registerPlugin<VolumeSchedulerPluginInterface>(
  'VolumeSchedulerPlugin'
);

const WEB_STORAGE_KEY = 'smart_volume_scheduler_web_preview_v3';

interface WebPreviewState {
  schedulerEnabled: boolean;
  schedules: VolumeSchedule[];
  profiles: VolumeProfile[];
  defaultProfileId: string | null;
}

export function isNativeAndroid(): boolean {
  return Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android';
}

export function createDefaultStreamsMap(
  overrides: Partial<RuleStreamsMap> = {}
): RuleStreamsMap {
  return {
    media: overrides.media ?? { enabled: false, percentage: 70 },
    ringtone: overrides.ringtone ?? { enabled: false, percentage: 70 },
    notification: overrides.notification ?? { enabled: false, percentage: 70 },
    alarm: overrides.alarm ?? { enabled: false, percentage: 70 },
    voice_call: overrides.voice_call ?? { enabled: false, percentage: 70 },
  };
}

function normalizeSchedule(raw: Partial<VolumeSchedule> & { stream?: AudioStreamKey; percentage?: number }): VolumeSchedule {
  const streams: RuleStreamsMap = createDefaultStreamsMap();
  if (raw.streams) {
    (['media', 'ringtone', 'notification', 'alarm', 'voice_call'] as AudioStreamKey[]).forEach(
      (k) => {
        const entry = raw.streams?.[k];
        if (entry) {
          streams[k] = {
            enabled: Boolean(entry.enabled),
            percentage: Math.min(100, Math.max(0, Math.round(entry.percentage ?? 70))),
          };
        }
      }
    );
  } else if (raw.stream) {
    streams[raw.stream] = {
      enabled: true,
      percentage: Math.min(100, Math.max(0, Math.round(raw.percentage ?? 80))),
    };
  }

  return {
    id: raw.id || `sched-${Date.now()}`,
    label: raw.label ?? '',
    time: raw.time ?? '08:00',
    streams,
    days: Array.isArray(raw.days) && raw.days.length > 0 ? [...raw.days].sort() : [0, 1, 2, 3, 4, 5, 6],
    enabled: raw.enabled ?? true,
    nextTriggerAt: raw.nextTriggerAt ?? 0,
    lastExecutedAt: raw.lastExecutedAt ?? 0,
    lastExecutedSlotMillis: raw.lastExecutedSlotMillis ?? 0,
    lastExecutionSuccess: raw.lastExecutionSuccess ?? true,
    lastExecutionMessage: raw.lastExecutionMessage ?? '',
  };
}

function computeNextLocalTrigger(time: string, days: number[]): number {
  const [hStr, mStr] = time.split(':');
  const hour = Math.min(23, Math.max(0, Number.parseInt(hStr || '8', 10) || 8));
  const minute = Math.min(59, Math.max(0, Number.parseInt(mStr || '0', 10) || 0));
  const activeDays = days.length > 0 ? new Set(days) : new Set([0, 1, 2, 3, 4, 5, 6]);
  const now = Date.now();

  for (let offset = 0; offset <= 7; offset++) {
    const candidate = new Date(now);
    candidate.setDate(candidate.getDate() + offset);
    candidate.setHours(hour, minute, 0, 0);
    if (activeDays.has(candidate.getDay()) && candidate.getTime() > now + 500) {
      return candidate.getTime();
    }
  }
  return now + 86400000;
}

function normalizeProfile(raw: Partial<VolumeProfile>): VolumeProfile {
  const streams: RuleStreamsMap = {
    media: {
      enabled: raw.streams?.media?.enabled ?? true,
      percentage: Math.min(100, Math.max(0, Math.round(raw.streams?.media?.percentage ?? 70))),
    },
    ringtone: {
      enabled: raw.streams?.ringtone?.enabled ?? true,
      percentage: Math.min(100, Math.max(0, Math.round(raw.streams?.ringtone?.percentage ?? 70))),
    },
    notification: {
      enabled: raw.streams?.notification?.enabled ?? true,
      percentage: Math.min(100, Math.max(0, Math.round(raw.streams?.notification?.percentage ?? 70))),
    },
    alarm: {
      enabled: raw.streams?.alarm?.enabled ?? true,
      percentage: Math.min(100, Math.max(0, Math.round(raw.streams?.alarm?.percentage ?? 70))),
    },
    voice_call: {
      enabled: raw.streams?.voice_call?.enabled ?? true,
      percentage: Math.min(100, Math.max(0, Math.round(raw.streams?.voice_call?.percentage ?? 70))),
    },
  };

  return {
    id: raw.id || `prof-${Date.now()}`,
    name: raw.name?.trim() || 'Profile',
    icon: raw.icon || 'sun',
    startTime: raw.startTime ?? '06:00',
    endTime: raw.endTime ?? '20:00',
    streams,
    enabled: raw.enabled ?? true,
    priority: typeof raw.priority === 'number' ? raw.priority : 0,
    nextTransitionAt: raw.nextTransitionAt ?? 0,
    lastActivatedAt: raw.lastActivatedAt ?? 0,
    lastActivatedSlotMillis: raw.lastActivatedSlotMillis ?? 0,
    lastExecutionSuccess: raw.lastExecutionSuccess ?? true,
    lastExecutionMessage: raw.lastExecutionMessage ?? '',
  };
}

function computeNextProfileStartPreview(startTime: string): number {
  return computeNextLocalTrigger(startTime, [0, 1, 2, 3, 4, 5, 6]);
}

function computeNextProfileTransitionPreview(profiles: VolumeProfile[]): number {
  const enabled = profiles.filter((p) => p.enabled);
  if (enabled.length === 0) return 0;
  const boundaries = new Set<number>();
  enabled.forEach((p) => {
    boundaries.add(toMinuteOfDay(p.startTime));
    boundaries.add((toMinuteOfDay(p.endTime) + 1) % 1440);
  });

  const now = Date.now();
  let best = 0;
  for (let offset = 0; offset <= 2; offset++) {
    boundaries.forEach((minOfDay) => {
      const candidate = new Date(now);
      candidate.setDate(candidate.getDate() + offset);
      candidate.setHours(Math.floor(minOfDay / 60), minOfDay % 60, 0, 0);
      const ts = candidate.getTime();
      if (ts > now + 500 && (best === 0 || ts < best)) {
        best = ts;
      }
    });
  }
  return best;
}

function detectWebProfileOverlaps(profiles: VolumeProfile[]): ProfileOverlapReport {
  const enabled = profiles.filter((p) => p.enabled);
  const overlapping = new Set<string>();
  const ambiguous = new Set<string>();
  const warnings: string[] = [];

  for (let i = 0; i < enabled.length; i++) {
    for (let j = i + 1; j < enabled.length; j++) {
      const a = enabled[i];
      const b = enabled[j];
      if (doProfileRangesOverlap(a, b)) {
        overlapping.add(a.id);
        overlapping.add(b.id);
        if (a.priority === b.priority) {
          ambiguous.add(a.id);
          ambiguous.add(b.id);
          warnings.push(
            `Overlap conflict: "${a.name}" (${a.startTime}–${a.endTime}) overlaps with "${b.name}" (${b.startTime}–${b.endTime}) at equal priority (${a.priority}). Adjust times or assign explicit priority.`
          );
        } else {
          const higher = a.priority > b.priority ? a : b;
          const lower = a.priority > b.priority ? b : a;
          warnings.push(
            `Priority overlap: "${higher.name}" (Priority ${higher.priority}) takes precedence over "${lower.name}" (Priority ${lower.priority}) during shared hours.`
          );
        }
      }
    }
  }

  return {
    hasAnyOverlap: overlapping.size > 0,
    hasAmbiguousOverlap: ambiguous.size > 0,
    overlappingProfileIds: Array.from(overlapping),
    ambiguousProfileIds: Array.from(ambiguous),
    warnings,
  };
}

function resolveWebProfile(
  profiles: VolumeProfile[],
  schedulerEnabled: boolean,
  defaultProfileId: string | null
): ProfileResolution {
  if (!schedulerEnabled) {
    return {
      status: 'paused',
      activeProfile: null,
      isFallback: false,
      conflictingProfiles: [],
      activeSlotMillis: 0,
      message: 'Master scheduler is paused.',
    };
  }
  const enabled = profiles.filter((p) => p.enabled);
  const now = new Date();
  const nowMin = now.getHours() * 60 + now.getMinutes();
  const matching = enabled.filter((p) =>
    isMinuteInProfileRange(nowMin, p.startTime, p.endTime)
  );

  if (matching.length > 0) {
    const maxPrio = Math.max(...matching.map((p) => p.priority));
    const top = matching.filter((p) => p.priority === maxPrio);
    if (top.length > 1) {
      const names = top.map((p) => `"${p.name}"`).join(' & ');
      return {
        status: 'conflict',
        activeProfile: null,
        isFallback: false,
        conflictingProfiles: top,
        activeSlotMillis: 0,
        message: `Ambiguous overlap: ${names} match current time with equal priority (${maxPrio}). Volume left unchanged.`,
      };
    }
    const winner = top[0];
    return {
      status: 'active',
      activeProfile: winner,
      isFallback: false,
      conflictingProfiles: [],
      activeSlotMillis: 0,
      message: `${winner.name} (${winner.startTime}–${winner.endTime}) is active.`,
    };
  }

  const fallback = defaultProfileId
    ? enabled.find((p) => p.id === defaultProfileId) ?? null
    : null;
  if (fallback) {
    return {
      status: 'fallback',
      activeProfile: fallback,
      isFallback: true,
      conflictingProfiles: [],
      activeSlotMillis: 0,
      message: `No timed profile active; fallback default "${fallback.name}" is active.`,
    };
  }

  return {
    status: 'none',
    activeProfile: null,
    isFallback: false,
    conflictingProfiles: [],
    activeSlotMillis: 0,
    message: 'No profile active for the current time. System volume remains unchanged.',
  };
}

function loadWebPreviewState(): WebPreviewState {
  const defaultProfiles: VolumeProfile[] = [
    {
      id: 'default-profile-day',
      name: 'Day Profile',
      icon: 'sun',
      startTime: '06:00',
      endTime: '20:00',
      streams: {
        media: { enabled: true, percentage: 70 },
        ringtone: { enabled: true, percentage: 100 },
        notification: { enabled: true, percentage: 100 },
        alarm: { enabled: true, percentage: 80 },
        voice_call: { enabled: true, percentage: 100 },
      },
      enabled: true,
      priority: 0,
      nextTransitionAt: computeNextProfileStartPreview('06:00'),
      lastActivatedAt: 0,
      lastActivatedSlotMillis: 0,
      lastExecutionSuccess: true,
      lastExecutionMessage: '',
    },
    {
      id: 'default-profile-night',
      name: 'Night Profile',
      icon: 'moon',
      startTime: '20:01',
      endTime: '05:59',
      streams: {
        media: { enabled: true, percentage: 30 },
        ringtone: { enabled: true, percentage: 50 },
        notification: { enabled: true, percentage: 50 },
        alarm: { enabled: true, percentage: 50 },
        voice_call: { enabled: true, percentage: 50 },
      },
      enabled: true,
      priority: 0,
      nextTransitionAt: computeNextProfileStartPreview('20:01'),
      lastActivatedAt: 0,
      lastActivatedSlotMillis: 0,
      lastExecutionSuccess: true,
      lastExecutionMessage: '',
    },
  ];

  const defaultState: WebPreviewState = {
    schedulerEnabled: true,
    schedules: [],
    profiles: defaultProfiles,
    defaultProfileId: null,
  };

  try {
    const raw = localStorage.getItem(WEB_STORAGE_KEY);
    if (!raw) {
      localStorage.setItem(WEB_STORAGE_KEY, JSON.stringify(defaultState));
      return defaultState;
    }
    const parsed = JSON.parse(raw) as Partial<WebPreviewState>;
    const schedulerEnabled = parsed.schedulerEnabled ?? true;
    const schedules = (parsed.schedules || []).map((item) => {
      const normalized = normalizeSchedule(item);
      return {
        ...normalized,
        nextTriggerAt:
          schedulerEnabled && normalized.enabled
            ? computeNextLocalTrigger(normalized.time, normalized.days)
            : 0,
      };
    });
    const profiles = (parsed.profiles && parsed.profiles.length > 0
      ? parsed.profiles
      : defaultProfiles
    ).map((p) => {
      const norm = normalizeProfile(p);
      return {
        ...norm,
        nextTransitionAt:
          schedulerEnabled && norm.enabled
            ? computeNextProfileStartPreview(norm.startTime)
            : 0,
      };
    });

    return {
      schedulerEnabled,
      schedules: schedules.sort((a, b) => a.time.localeCompare(b.time)),
      profiles: profiles.sort((a, b) => a.startTime.localeCompare(b.startTime) || a.name.localeCompare(b.name)),
      defaultProfileId: parsed.defaultProfileId ?? null,
    };
  } catch {
    return defaultState;
  }
}

function saveWebPreviewState(state: WebPreviewState): void {
  try {
    localStorage.setItem(WEB_STORAGE_KEY, JSON.stringify(state));
  } catch {
    // Ignore storage quota errors in preview
  }
}

function getPreviewUnobservedStreams(): Record<AudioStreamKey, StreamSnapshot> {
  return {
    media: { stream: 'media', label: 'Media', currentIndex: 0, minIndex: 0, maxIndex: 15, percentage: 0 },
    ringtone: { stream: 'ringtone', label: 'Ringtone', currentIndex: 0, minIndex: 0, maxIndex: 15, percentage: 0 },
    notification: { stream: 'notification', label: 'Notification', currentIndex: 0, minIndex: 0, maxIndex: 15, percentage: 0 },
    alarm: { stream: 'alarm', label: 'Alarm', currentIndex: 0, minIndex: 0, maxIndex: 15, percentage: 0 },
    voice_call: { stream: 'voice_call', label: 'Voice Call', currentIndex: 1, minIndex: 1, maxIndex: 5, percentage: 20 },
  };
}

function buildWebSettings(): VolumeSettingsResponse {
  const state = loadWebPreviewState();
  const next = state.schedulerEnabled
    ? state.schedules
        .filter((s) => s.enabled && s.nextTriggerAt > 0)
        .sort((a, b) => a.nextTriggerAt - b.nextTriggerAt)[0] ?? null
    : null;
  const profileOverlap = detectWebProfileOverlaps(state.profiles);
  const activeProfileResolution = resolveWebProfile(
    state.profiles,
    state.schedulerEnabled,
    state.defaultProfileId
  );
  const nextProfileTransitionAt = state.schedulerEnabled
    ? computeNextProfileTransitionPreview(state.profiles)
    : 0;

  return {
    native: false,
    streams: getPreviewUnobservedStreams(),
    ringerMode: 'normal',
    audioMode: 'WEB_PREVIEW',
    isVolumeFixed: false,
    isDoNotDisturbActive: false,
    streamsAliased: false,
    streamsAliasedNote: '',
    schedulerEnabled: state.schedulerEnabled,
    schedules: state.schedules,
    profiles: state.profiles,
    defaultProfileId: state.defaultProfileId,
    profileOverlap,
    activeProfileResolution,
    nextProfileTransitionAt,
    nextScheduledChange: next,
    bootRestore: {
      lastBootRestoreAt: 0,
      lastBootRestoreReason: '',
      lastBootRestoredCount: 0,
    },
    recentLogs: [],
    lastTestAlarmResult: null,
    deviceInfo: null,
  };
}

function buildWebPermissionStatus(): PermissionStatusResponse {
  return {
    native: false,
    exactAlarm: {
      granted: false,
      required: true,
      sdkSupported: true,
      detail: 'Unavailable in web browser. Live AlarmManager permission is verified on Android APK.',
    },
    dndPolicyAccess: {
      granted: false,
      required: true,
      detail: 'Unavailable in web browser. Verified via NotificationManager on Android APK.',
    },
    batteryOptimizationExempt: {
      granted: false,
      recommended: true,
      detail: 'Unavailable in web browser. Verified via PowerManager on Android APK.',
    },
    postNotifications: {
      granted: false,
      required: false,
      detail: 'Optional permission (not required for volume scheduling or testing).',
    },
    bootReceiverRegistered: false,
  };
}

export const volumeScheduler = {
  async getVolumeSettings(): Promise<VolumeSettingsResponse> {
    if (isNativeAndroid()) {
      const res = await NativeVolumeScheduler.getVolumeSettings();
      return {
        ...res,
        native: true,
        schedules: (res.schedules || []).map((s) => normalizeSchedule(s)),
        profiles: (res.profiles || []).map((p) => normalizeProfile(p)),
        nextScheduledChange: res.nextScheduledChange
          ? normalizeSchedule(res.nextScheduledChange)
          : null,
      };
    }
    return buildWebSettings();
  },

  async setVolume(
    stream: AudioStreamKey,
    percentage: number,
    showUi = false
  ): Promise<VolumeApplyResult> {
    if (isNativeAndroid()) {
      return NativeVolumeScheduler.setVolume({ stream, percentage, showUi });
    }
    return {
      success: false,
      appliedExactTarget: false,
      stream,
      requestedPercentage: percentage,
      indexBefore: 0,
      targetIndex: 0,
      actualIndex: 0,
      actualPercentage: 0,
      minIndex: 0,
      maxIndex: 15,
      dndBlocked: false,
      fixedVolume: false,
      streamUnavailable: true,
      failureReason: 'Browser preview cannot modify Android AudioManager streams.',
      message:
        'Browser preview cannot modify Android AudioManager streams. Install the Android APK to apply real system volume changes.',
    };
  },

  async scheduleVolumeChange(
    params: ScheduleVolumeChangeParams
  ): Promise<ScheduleVolumeChangeResponse> {
    if (isNativeAndroid()) {
      const res = await NativeVolumeScheduler.scheduleVolumeChange(params);
      return {
        ...res,
        schedule: normalizeSchedule(res.schedule),
      };
    }

    const state = loadWebPreviewState();
    const id = params.id || `sched-${Date.now()}`;
    const existing = state.schedules.find((s) => s.id === id);
    const enabled = params.enabled ?? existing?.enabled ?? true;
    const days = params.days.length > 0 ? [...params.days].sort() : [0, 1, 2, 3, 4, 5, 6];
    const updatedSchedule: VolumeSchedule = normalizeSchedule({
      id,
      label: (params.label ?? existing?.label ?? '').trim(),
      time: params.time,
      streams: params.streams,
      days,
      enabled,
      nextTriggerAt:
        state.schedulerEnabled && enabled
          ? computeNextLocalTrigger(params.time, days)
          : 0,
      lastExecutedAt: existing?.lastExecutedAt ?? 0,
      lastExecutedSlotMillis: existing?.lastExecutedSlotMillis ?? 0,
      lastExecutionSuccess: existing?.lastExecutionSuccess ?? true,
      lastExecutionMessage: existing?.lastExecutionMessage ?? '',
    });

    const nextList = state.schedules.some((s) => s.id === id)
      ? state.schedules.map((s) => (s.id === id ? updatedSchedule : s))
      : [...state.schedules, updatedSchedule];

    saveWebPreviewState({
      ...state,
      schedules: nextList.sort((a, b) => a.time.localeCompare(b.time)),
    });

    return {
      success: true,
      schedule: updatedSchedule,
      scheduled: false,
      exact: false,
      mode: 'web_preview',
      executedImmediately: false,
      message:
        'Saved in web preview storage. Native AlarmManager scheduling activates inside the Android APK.',
    };
  },

  async getScheduledChanges(): Promise<GetScheduledChangesResponse> {
    if (isNativeAndroid()) {
      const res = await NativeVolumeScheduler.getScheduledChanges();
      return {
        ...res,
        schedules: (res.schedules || []).map((s) => normalizeSchedule(s)),
        nextScheduledChange: res.nextScheduledChange
          ? normalizeSchedule(res.nextScheduledChange)
          : null,
      };
    }
    const settings = buildWebSettings();
    return {
      schedulerEnabled: settings.schedulerEnabled,
      schedules: settings.schedules,
      nextScheduledChange: settings.nextScheduledChange,
    };
  },

  async deleteScheduledChange(id: string): Promise<DeleteScheduledChangeResponse> {
    if (isNativeAndroid()) {
      const res = await NativeVolumeScheduler.deleteScheduledChange({ id });
      return {
        ...res,
        schedules: (res.schedules || []).map((s) => normalizeSchedule(s)),
      };
    }
    const state = loadWebPreviewState();
    const remaining = state.schedules.filter((s) => s.id !== id);
    saveWebPreviewState({ ...state, schedules: remaining });
    return {
      success: remaining.length !== state.schedules.length,
      deletedId: id,
      schedules: remaining,
    };
  },

  async toggleScheduleEnabled(
    id: string,
    enabled: boolean
  ): Promise<{
    success: boolean;
    schedule: VolumeSchedule;
    mode: string;
    executedImmediately?: boolean;
    message: string;
  }> {
    if (isNativeAndroid()) {
      const res = await NativeVolumeScheduler.toggleScheduleEnabled({ id, enabled });
      return {
        ...res,
        schedule: normalizeSchedule(res.schedule),
      };
    }
    const state = loadWebPreviewState();
    let updated: VolumeSchedule | null = null;
    const nextList = state.schedules.map((s) => {
      if (s.id !== id) return s;
      updated = {
        ...s,
        enabled,
        nextTriggerAt:
          state.schedulerEnabled && enabled
            ? computeNextLocalTrigger(s.time, s.days)
            : 0,
      };
      return updated;
    });
    saveWebPreviewState({ ...state, schedules: nextList });
    if (!updated) {
      throw new Error(`Schedule not found: ${id}`);
    }
    return {
      success: true,
      schedule: updated,
      mode: 'web_preview',
      message: 'Updated in web preview storage.',
    };
  },

  async getVolumeProfiles(): Promise<GetVolumeProfilesResponse> {
    if (isNativeAndroid()) {
      const res = await NativeVolumeScheduler.getVolumeProfiles();
      return {
        ...res,
        profiles: (res.profiles || []).map((p) => normalizeProfile(p)),
      };
    }
    const state = loadWebPreviewState();
    return {
      schedulerEnabled: state.schedulerEnabled,
      profiles: state.profiles,
      defaultProfileId: state.defaultProfileId,
      profileOverlap: detectWebProfileOverlaps(state.profiles),
      activeProfileResolution: resolveWebProfile(
        state.profiles,
        state.schedulerEnabled,
        state.defaultProfileId
      ),
      nextProfileTransitionAt: state.schedulerEnabled
        ? computeNextProfileTransitionPreview(state.profiles)
        : 0,
    };
  },

  async saveVolumeProfile(
    params: SaveVolumeProfileParams
  ): Promise<SaveVolumeProfileResponse> {
    if (isNativeAndroid()) {
      const res = await NativeVolumeScheduler.saveVolumeProfile(params);
      return {
        ...res,
        profile: normalizeProfile(res.profile),
      };
    }
    const state = loadWebPreviewState();
    const id = params.id || `prof-${Date.now()}`;
    const existing = state.profiles.find((p) => p.id === id);
    const updated = normalizeProfile({
      id,
      name: params.name.trim(),
      icon: params.icon,
      startTime: params.startTime,
      endTime: params.endTime,
      streams: params.streams,
      enabled: params.enabled,
      priority: params.priority,
      nextTransitionAt:
        state.schedulerEnabled && params.enabled
          ? computeNextProfileStartPreview(params.startTime)
          : 0,
      lastActivatedAt: existing?.lastActivatedAt ?? 0,
      lastActivatedSlotMillis: existing?.lastActivatedSlotMillis ?? 0,
      lastExecutionSuccess: existing?.lastExecutionSuccess ?? true,
      lastExecutionMessage: existing?.lastExecutionMessage ?? '',
    });

    const nextProfiles = state.profiles.some((p) => p.id === id)
      ? state.profiles.map((p) => (p.id === id ? updated : p))
      : [...state.profiles, updated];
    const sorted = nextProfiles.sort(
      (a, b) => a.startTime.localeCompare(b.startTime) || a.name.localeCompare(b.name)
    );
    saveWebPreviewState({ ...state, profiles: sorted });

    const profileOverlap = detectWebProfileOverlaps(sorted);
    const activeProfileResolution = resolveWebProfile(
      sorted,
      state.schedulerEnabled,
      state.defaultProfileId
    );
    return {
      success: true,
      profile: updated,
      executedImmediately: activeProfileResolution.activeProfile?.id === updated.id,
      mode: 'web_preview',
      profileOverlap,
      activeProfileResolution,
      nextProfileTransitionAt: state.schedulerEnabled
        ? computeNextProfileTransitionPreview(sorted)
        : 0,
      message: `Saved "${updated.name}" in web preview. ${activeProfileResolution.message}`,
    };
  },

  async deleteVolumeProfile(id: string): Promise<DeleteVolumeProfileResponse> {
    if (isNativeAndroid()) {
      const res = await NativeVolumeScheduler.deleteVolumeProfile({ id });
      return {
        ...res,
        profiles: (res.profiles || []).map((p) => normalizeProfile(p)),
      };
    }
    const state = loadWebPreviewState();
    const remaining = state.profiles.filter((p) => p.id !== id);
    const defaultProfileId =
      state.defaultProfileId === id ? null : state.defaultProfileId;
    saveWebPreviewState({ ...state, profiles: remaining, defaultProfileId });
    const profileOverlap = detectWebProfileOverlaps(remaining);
    const activeProfileResolution = resolveWebProfile(
      remaining,
      state.schedulerEnabled,
      defaultProfileId
    );
    return {
      success: remaining.length !== state.profiles.length,
      deletedId: id,
      profiles: remaining,
      profileOverlap,
      activeProfileResolution,
      message: `Deleted profile in web preview. ${activeProfileResolution.message}`,
    };
  },

  async toggleProfileEnabled(
    id: string,
    enabled: boolean
  ): Promise<ToggleProfileResponse> {
    if (isNativeAndroid()) {
      const res = await NativeVolumeScheduler.toggleProfileEnabled({ id, enabled });
      return {
        ...res,
        profile: normalizeProfile(res.profile),
      };
    }
    const state = loadWebPreviewState();
    let target: VolumeProfile | null = null;
    const nextProfiles = state.profiles.map((p) => {
      if (p.id !== id) return p;
      target = {
        ...p,
        enabled,
        nextTransitionAt:
          state.schedulerEnabled && enabled
            ? computeNextProfileStartPreview(p.startTime)
            : 0,
      };
      return target;
    });
    if (!target) {
      throw new Error(`Profile not found: ${id}`);
    }
    saveWebPreviewState({ ...state, profiles: nextProfiles });
    const profileOverlap = detectWebProfileOverlaps(nextProfiles);
    const activeProfileResolution = resolveWebProfile(
      nextProfiles,
      state.schedulerEnabled,
      state.defaultProfileId
    );
    return {
      success: true,
      profile: target,
      executedImmediately: activeProfileResolution.activeProfile?.id === id,
      mode: 'web_preview',
      profileOverlap,
      activeProfileResolution,
      message: `${enabled ? 'Enabled' : 'Disabled'} "${(target as VolumeProfile).name}". ${activeProfileResolution.message}`,
    };
  },

  async setDefaultProfile(profileId: string | null): Promise<SetDefaultProfileResponse> {
    if (isNativeAndroid()) {
      return NativeVolumeScheduler.setDefaultProfile({ profileId });
    }
    const state = loadWebPreviewState();
    saveWebPreviewState({ ...state, defaultProfileId: profileId });
    const activeProfileResolution = resolveWebProfile(
      state.profiles,
      state.schedulerEnabled,
      profileId
    );
    return {
      success: true,
      defaultProfileId: profileId,
      activeProfileResolution,
      message: profileId
        ? 'Fallback default profile updated in web preview.'
        : 'Fallback default cleared in web preview.',
    };
  },

  async enableScheduler(): Promise<SchedulerToggleResponse> {
    if (isNativeAndroid()) {
      return NativeVolumeScheduler.enableScheduler();
    }
    const state = loadWebPreviewState();
    const updated = state.schedules.map((s) => ({
      ...s,
      nextTriggerAt: s.enabled ? computeNextLocalTrigger(s.time, s.days) : 0,
    }));
    const updatedProfiles = state.profiles.map((p) => ({
      ...p,
      nextTransitionAt: p.enabled ? computeNextProfileStartPreview(p.startTime) : 0,
    }));
    saveWebPreviewState({
      ...state,
      schedulerEnabled: true,
      schedules: updated,
      profiles: updatedProfiles,
    });
    return {
      success: true,
      schedulerEnabled: true,
      schedules: updated,
      message:
        'Scheduler enabled in preview. Native AlarmManager alarms require the Android APK.',
    };
  },

  async disableScheduler(): Promise<SchedulerToggleResponse> {
    if (isNativeAndroid()) {
      return NativeVolumeScheduler.disableScheduler();
    }
    const state = loadWebPreviewState();
    const updated = state.schedules.map((s) => ({ ...s, nextTriggerAt: 0 }));
    const updatedProfiles = state.profiles.map((p) => ({ ...p, nextTransitionAt: 0 }));
    saveWebPreviewState({
      ...state,
      schedulerEnabled: false,
      schedules: updated,
      profiles: updatedProfiles,
    });
    return {
      success: true,
      schedulerEnabled: false,
      schedules: updated,
      message: 'Scheduler paused in preview.',
    };
  },

  async getPermissionStatus(): Promise<PermissionStatusResponse> {
    if (isNativeAndroid()) {
      const res = await NativeVolumeScheduler.getPermissionStatus();
      return { ...res, native: true };
    }
    return buildWebPermissionStatus();
  },

  async requestRequiredPermissions(
    permissionType: PermissionActionType = 'auto'
  ): Promise<RequestPermissionResponse> {
    if (isNativeAndroid()) {
      const res = await NativeVolumeScheduler.requestRequiredPermissions({
        permissionType,
      });
      return {
        ...res,
        status: { ...res.status, native: true },
      };
    }
    return {
      opened: false,
      granted: false,
      action: permissionType,
      message:
        'Android system permission screens can only be opened inside the installed Android APK.',
      status: buildWebPermissionStatus(),
    };
  },

  async testVolumeStream(
    stream: AudioStreamKey,
    percentage?: number
  ): Promise<StreamTestResult> {
    if (isNativeAndroid()) {
      return NativeVolumeScheduler.testVolumeStream({
        stream,
        percentage,
      });
    }
    return {
      stream,
      requestedPercentage: percentage ?? 0,
      indexBefore: 0,
      targetIndex: 0,
      actualIndex: 0,
      minIndex: 0,
      maxIndex: 15,
      actualPercentage: 0,
      volumeApplied: false,
      playbackStarted: false,
      streamVerified: false,
      callSessionActive: false,
      audioMode: 'WEB_PREVIEW',
      ringerMode: 'normal',
      dndBlocked: false,
      fixedVolume: false,
      failureReason: 'Running in web browser, not Android Capacitor runtime.',
      source: 'FOREGROUND_TEST',
      timestamp: Date.now(),
      message:
        'Native AudioManager stream test failed: running in web browser, not Android Capacitor runtime.',
    };
  },

  async scheduleTestAlarm(
    secondsFromNow: number,
    stream: AudioStreamKey,
    percentage: number
  ): Promise<TestAlarmResponse> {
    if (isNativeAndroid()) {
      return NativeVolumeScheduler.scheduleTestAlarm({
        secondsFromNow,
        stream,
        percentage,
      });
    }
    return {
      success: false,
      exact: false,
      nextTriggerAt: 0,
      mode: 'unavailable',
      message:
        'Native AlarmManager test cannot be scheduled in a web browser. Install the Android APK to test background alarms.',
    };
  },

  async getExecutionLogs(): Promise<{
    logs: ExecutionLogEntry[];
    bootRestore: VolumeSettingsResponse['bootRestore'];
    lastTestAlarmResult: StreamTestResult | null;
  }> {
    if (isNativeAndroid()) {
      return NativeVolumeScheduler.getExecutionLogs();
    }
    return {
      logs: [],
      bootRestore: {
        lastBootRestoreAt: 0,
        lastBootRestoreReason: '',
        lastBootRestoredCount: 0,
      },
      lastTestAlarmResult: null,
    };
  },

  async getAudioModeProbe(): Promise<AudioModeProbeResponse> {
    if (isNativeAndroid()) {
      const res = await NativeVolumeScheduler.getAudioModeProbe();
      return { ...res, native: true };
    }
    return {
      native: false,
      audioMode: 'WEB_PREVIEW',
      ringerMode: 'normal',
      isSpeakerphoneOn: false,
      isBluetoothScoOn: false,
      isMusicActive: false,
      streams: getPreviewUnobservedStreams(),
    };
  },

  async subscribeToNativeUpdates(
    callback: (state: VolumeSettingsResponse) => void
  ): Promise<() => void> {
    if (!isNativeAndroid()) {
      return () => {};
    }
    const handle = await NativeVolumeScheduler.addListener(
      'volumeStateChanged',
      (state) =>
        callback({
          ...state,
          native: true,
          schedules: (state.schedules || []).map((s) => normalizeSchedule(s)),
          profiles: (state.profiles || []).map((p) => normalizeProfile(p)),
          nextScheduledChange: state.nextScheduledChange
            ? normalizeSchedule(state.nextScheduledChange)
            : null,
        })
    );
    return () => {
      void handle.remove();
    };
  },
};
