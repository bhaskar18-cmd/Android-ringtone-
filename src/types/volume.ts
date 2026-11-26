export type AudioStreamKey =
  | 'media'
  | 'ringtone'
  | 'notification'
  | 'alarm'
  | 'voice_call';

export interface StreamSnapshot {
  stream: AudioStreamKey;
  label: string;
  currentIndex: number;
  minIndex: number;
  maxIndex: number;
  percentage: number;
}

export interface StreamRuleConfig {
  enabled: boolean;
  percentage: number;
}

export type RuleStreamsMap = Record<AudioStreamKey, StreamRuleConfig>;

export interface VolumeSchedule {
  id: string;
  label: string;
  time: string; // 24-hour local HH:mm
  streams: RuleStreamsMap;
  days: number[]; // 0 = Sun, 1 = Mon, ..., 6 = Sat
  enabled: boolean;
  nextTriggerAt: number;
  lastExecutedAt: number;
  lastExecutionSuccess: boolean;
  lastExecutionMessage: string;
}

export interface BootRestoreInfo {
  lastBootRestoreAt: number;
  lastBootRestoreReason: string;
  lastBootRestoredCount: number;
}

export interface ExecutionLogEntry {
  id: string;
  timestamp: number;
  source:
    | 'SCHEDULE'
    | 'BACKGROUND_TEST_ALARM'
    | 'MANUAL'
    | 'FOREGROUND_TEST'
    | 'BOOT_RESTORE'
    | 'SYSTEM_EVENT'
    | string;
  scheduleId: string;
  stream: string;
  requestedPercentage: number;
  actualIndex: number;
  maxIndex: number;
  actualPercentage: number;
  success: boolean;
  message: string;
}

export interface DeviceInfo {
  manufacturer: string;
  model: string;
  androidVersion: string;
  sdkInt: number;
}

export interface StreamTestResult {
  stream: AudioStreamKey | string;
  requestedPercentage: number;
  targetIndex: number;
  actualIndex: number;
  minIndex: number;
  maxIndex: number;
  actualPercentage: number;
  volumeApplied: boolean;
  playbackStarted: boolean;
  streamVerified: boolean;
  callSessionActive: boolean;
  audioMode: string;
  ringerMode: string;
  dndBlocked: boolean;
  fixedVolume: boolean;
  source: string;
  timestamp: number;
  message: string;
}

export interface VolumeSettingsResponse {
  native: boolean;
  streams: Record<AudioStreamKey, StreamSnapshot>;
  ringerMode: 'normal' | 'vibrate' | 'silent';
  audioMode: string;
  isVolumeFixed: boolean;
  isDoNotDisturbActive: boolean;
  schedulerEnabled: boolean;
  schedules: VolumeSchedule[];
  nextScheduledChange: VolumeSchedule | null;
  bootRestore: BootRestoreInfo;
  recentLogs: ExecutionLogEntry[];
  lastTestAlarmResult: StreamTestResult | null;
  deviceInfo: DeviceInfo | null;
}

export interface VolumeApplyResult {
  success: boolean;
  appliedExactTarget: boolean;
  stream: string;
  requestedPercentage: number;
  targetIndex: number;
  actualIndex: number;
  actualPercentage: number;
  minIndex: number;
  maxIndex: number;
  dndBlocked: boolean;
  fixedVolume: boolean;
  message: string;
}

export interface ScheduleVolumeChangeParams {
  id?: string;
  label?: string;
  time: string;
  streams: RuleStreamsMap;
  days: number[];
  enabled?: boolean;
}

export interface ScheduleVolumeChangeResponse {
  success: boolean;
  schedule: VolumeSchedule;
  scheduled: boolean;
  exact: boolean;
  mode: 'exact' | 'inexact_fallback' | 'disabled' | 'web_preview';
  message: string;
}

export interface GetScheduledChangesResponse {
  schedulerEnabled: boolean;
  schedules: VolumeSchedule[];
  nextScheduledChange: VolumeSchedule | null;
}

export interface DeleteScheduledChangeResponse {
  success: boolean;
  deletedId: string;
  schedules: VolumeSchedule[];
}

export interface SchedulerToggleResponse {
  success: boolean;
  schedulerEnabled: boolean;
  scheduledCount?: number;
  exactMode?: boolean;
  schedules?: VolumeSchedule[];
  message: string;
}

export interface PermissionItemStatus {
  granted: boolean;
  required?: boolean;
  recommended?: boolean;
  sdkSupported?: boolean;
  detail: string;
}

export interface PermissionStatusResponse {
  native: boolean;
  exactAlarm: PermissionItemStatus;
  dndPolicyAccess: PermissionItemStatus;
  batteryOptimizationExempt: PermissionItemStatus;
  postNotifications: PermissionItemStatus;
  bootReceiverRegistered: boolean;
}

export interface RequestPermissionResponse {
  opened: boolean;
  granted?: boolean;
  action?: string;
  message: string;
  status: PermissionStatusResponse;
}

export interface TestAlarmResponse {
  success: boolean;
  exact: boolean;
  nextTriggerAt: number;
  mode: string;
  message: string;
}

export interface AudioModeProbeResponse {
  native: boolean;
  audioMode: string;
  ringerMode: string;
  isSpeakerphoneOn: boolean;
  isBluetoothScoOn: boolean;
  isMusicActive: boolean;
  streams: Record<AudioStreamKey, StreamSnapshot>;
}

export type PermissionActionType =
  | 'auto'
  | 'exact_alarm'
  | 'dnd_access'
  | 'battery_optimization'
  | 'notifications';

export interface VolumeSchedulerPluginInterface {
  getVolumeSettings(): Promise<VolumeSettingsResponse>;
  setVolume(options: {
    stream: AudioStreamKey;
    percentage: number;
    showUi?: boolean;
  }): Promise<VolumeApplyResult>;
  scheduleVolumeChange(
    options: ScheduleVolumeChangeParams
  ): Promise<ScheduleVolumeChangeResponse>;
  getScheduledChanges(): Promise<GetScheduledChangesResponse>;
  deleteScheduledChange(options: {
    id: string;
  }): Promise<DeleteScheduledChangeResponse>;
  toggleScheduleEnabled(options: {
    id: string;
    enabled: boolean;
  }): Promise<{
    success: boolean;
    schedule: VolumeSchedule;
    mode: string;
    message: string;
  }>;
  enableScheduler(): Promise<SchedulerToggleResponse>;
  disableScheduler(): Promise<SchedulerToggleResponse>;
  requestRequiredPermissions(options?: {
    permissionType?: PermissionActionType;
  }): Promise<RequestPermissionResponse>;
  getPermissionStatus(): Promise<PermissionStatusResponse>;
  testVolumeStream(options: {
    stream: AudioStreamKey;
    percentage?: number;
  }): Promise<StreamTestResult>;
  scheduleTestAlarm(options: {
    secondsFromNow: number;
    stream: AudioStreamKey;
    percentage: number;
  }): Promise<TestAlarmResponse>;
  getExecutionLogs(): Promise<{
    logs: ExecutionLogEntry[];
    bootRestore: BootRestoreInfo;
    lastTestAlarmResult: StreamTestResult | null;
  }>;
  getAudioModeProbe(): Promise<AudioModeProbeResponse>;
  addListener(
    eventName: 'volumeStateChanged',
    listenerFunc: (state: VolumeSettingsResponse) => void
  ): Promise<{ remove: () => Promise<void> }>;
}
