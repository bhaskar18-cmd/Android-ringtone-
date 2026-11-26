import { Capacitor, registerPlugin } from '@capacitor/core';
import type {
  AudioModeProbeResponse,
  AudioStreamKey,
  DeleteScheduledChangeResponse,
  ExecutionLogEntry,
  GetScheduledChangesResponse,
  PermissionActionType,
  PermissionStatusResponse,
  RequestPermissionResponse,
  RuleStreamsMap,
  ScheduleVolumeChangeParams,
  ScheduleVolumeChangeResponse,
  SchedulerToggleResponse,
  StreamSnapshot,
  StreamTestResult,
  TestAlarmResponse,
  VolumeApplyResult,
  VolumeSchedule,
  VolumeSchedulerPluginInterface,
  VolumeSettingsResponse,
} from '../types/volume';

const NativeVolumeScheduler = registerPlugin<VolumeSchedulerPluginInterface>(
  'VolumeSchedulerPlugin'
);

const WEB_STORAGE_KEY = 'smart_volume_scheduler_web_preview_v2';

interface WebPreviewState {
  schedulerEnabled: boolean;
  schedules: VolumeSchedule[];
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

function loadWebPreviewState(): WebPreviewState {
  const defaultState: WebPreviewState = {
    schedulerEnabled: true,
    schedules: [
      {
        id: 'default-day-0800',
        label: 'Day Mode',
        time: '08:00',
        streams: createDefaultStreamsMap({
          ringtone: { enabled: true, percentage: 100 },
          notification: { enabled: true, percentage: 100 },
        }),
        days: [0, 1, 2, 3, 4, 5, 6],
        enabled: true,
        nextTriggerAt: computeNextLocalTrigger('08:00', [0, 1, 2, 3, 4, 5, 6]),
        lastExecutedAt: 0,
        lastExecutionSuccess: true,
        lastExecutionMessage: '',
      },
      {
        id: 'default-night-2000',
        label: 'Night Mode',
        time: '20:00',
        streams: createDefaultStreamsMap({
          ringtone: { enabled: true, percentage: 50 },
          notification: { enabled: true, percentage: 50 },
        }),
        days: [0, 1, 2, 3, 4, 5, 6],
        enabled: true,
        nextTriggerAt: computeNextLocalTrigger('20:00', [0, 1, 2, 3, 4, 5, 6]),
        lastExecutedAt: 0,
        lastExecutionSuccess: true,
        lastExecutionMessage: '',
      },
    ],
  };

  try {
    const raw = localStorage.getItem(WEB_STORAGE_KEY);
    if (!raw) {
      localStorage.setItem(WEB_STORAGE_KEY, JSON.stringify(defaultState));
      return defaultState;
    }
    const parsed = JSON.parse(raw) as WebPreviewState;
    const schedules = (parsed.schedules || []).map((item) => {
      const normalized = normalizeSchedule(item);
      return {
        ...normalized,
        nextTriggerAt:
          parsed.schedulerEnabled && normalized.enabled
            ? computeNextLocalTrigger(normalized.time, normalized.days)
            : 0,
      };
    });
    return {
      schedulerEnabled: Boolean(parsed.schedulerEnabled),
      schedules: schedules.sort((a, b) => a.time.localeCompare(b.time)),
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

  return {
    native: false,
    streams: getPreviewUnobservedStreams(),
    ringerMode: 'normal',
    audioMode: 'WEB_PREVIEW',
    isVolumeFixed: false,
    isDoNotDisturbActive: false,
    schedulerEnabled: state.schedulerEnabled,
    schedules: state.schedules,
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
      targetIndex: 0,
      actualIndex: 0,
      actualPercentage: 0,
      minIndex: 0,
      maxIndex: 15,
      dndBlocked: false,
      fixedVolume: false,
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
      lastExecutionSuccess: existing?.lastExecutionSuccess ?? true,
      lastExecutionMessage: existing?.lastExecutionMessage ?? '',
    });

    const nextList = state.schedules.some((s) => s.id === id)
      ? state.schedules.map((s) => (s.id === id ? updatedSchedule : s))
      : [...state.schedules, updatedSchedule];

    saveWebPreviewState({
      schedulerEnabled: state.schedulerEnabled,
      schedules: nextList.sort((a, b) => a.time.localeCompare(b.time)),
    });

    return {
      success: true,
      schedule: updatedSchedule,
      scheduled: false,
      exact: false,
      mode: 'web_preview',
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
  ): Promise<{ success: boolean; schedule: VolumeSchedule; mode: string; message: string }> {
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

  async enableScheduler(): Promise<SchedulerToggleResponse> {
    if (isNativeAndroid()) {
      return NativeVolumeScheduler.enableScheduler();
    }
    const state = loadWebPreviewState();
    const updated = state.schedules.map((s) => ({
      ...s,
      nextTriggerAt: s.enabled ? computeNextLocalTrigger(s.time, s.days) : 0,
    }));
    saveWebPreviewState({ schedulerEnabled: true, schedules: updated });
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
    saveWebPreviewState({ schedulerEnabled: false, schedules: updated });
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
