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

export type ProfileIconKey =
  | 'sun'
  | 'moon'
  | 'briefcase'
  | 'home'
  | 'car'
  | 'bell'
  | 'volume'
  | 'shield';

export interface VolumeProfile {
  id: string;
  name: string;
  icon: ProfileIconKey | string;
  startTime: string; // 24-hour local HH:mm
  endTime: string; // 24-hour local HH:mm
  streams: RuleStreamsMap;
  enabled: boolean;
  priority: number;
  nextTransitionAt: number;
  lastActivatedAt: number;
  lastActivatedSlotMillis?: number;
  lastExecutionSuccess: boolean;
  lastExecutionMessage: string;
}

export interface ProfileOverlapReport {
  hasAnyOverlap: boolean;
  hasAmbiguousOverlap: boolean;
  overlappingProfileIds: string[];
  ambiguousProfileIds: string[];
  warnings: string[];
}

export interface ProfileResolution {
  status: 'active' | 'fallback' | 'none' | 'conflict' | 'paused' | string;
  activeProfile: VolumeProfile | null;
  isFallback: boolean;
  conflictingProfiles: VolumeProfile[];
  activeSlotMillis: number;
  message: string;
}

export interface VolumeSchedule {
  id: string;
  label: string;
  time: string; // 24-hour local HH:mm
  streams: RuleStreamsMap;
  days: number[]; // 0 = Sun, 1 = Mon, ..., 6 = Sat
  enabled: boolean;
  nextTriggerAt: number;
  lastExecutedAt: number;
  lastExecutedSlotMillis?: number;
  lastExecutionSuccess: boolean;
  lastExecutionMessage: string;
}

export interface BootRestoreInfo {
  lastBootRestoreAt: number;
  lastBootRestoreReason: string;
  lastBootRestoredCount: number;
}

export interface StreamExecutionDetail {
  stream: AudioStreamKey | string;
  requestedPercentage: number;
  indexBefore: number;
  targetIndex: number;
  indexAfter: number;
  minIndex: number;
  maxIndex: number;
  actualPercentage: number;
  success: boolean;
  failureReason: string;
}

export interface ExecutionLogEntry {
  id: string;
  timestamp: number;
  source:
    | 'PROFILE_SCHEDULE'
    | 'IMMEDIATE_PROFILE'
    | 'BOOT_PROFILE_RESTORE'
    | 'PROFILE_FALLBACK'
    | 'PROFILE_CONFLICT_SKIPPED'
    | 'PROFILE_SKIPPED'
    | 'SCHEDULE'
    | 'IMMEDIATE_PAST_TIME'
    | 'BACKGROUND_TEST_ALARM'
    | 'MANUAL'
    | 'FOREGROUND_TEST'
    | 'BOOT_RESTORE'
    | 'ALARM_CANCELED'
    | 'RULE_SKIPPED'
    | 'SYSTEM_EVENT'
    | string;
  ruleId?: string;
  scheduleId: string;
  ruleName?: string;
  activeProfileName?: string;
  scheduledTime?: string;
  stream: string;
  requestedPercentage: number;
  indexBefore?: number;
  actualIndex: number;
  maxIndex: number;
  actualPercentage: number;
  success: boolean;
  failureReason?: string;
  streamsAliasedNote?: string;
  streamResults?: StreamExecutionDetail[];
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
  indexBefore?: number;
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
  failureReason?: string;
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
  streamsAliased?: boolean;
  streamsAliasedNote?: string;
  schedulerEnabled: boolean;
  schedules: VolumeSchedule[];
  profiles?: VolumeProfile[];
  defaultProfileId?: string | null;
  profileOverlap?: ProfileOverlapReport;
  activeProfileResolution?: ProfileResolution;
  nextProfileTransitionAt?: number;
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
  indexBefore?: number;
  targetIndex: number;
  actualIndex: number;
  actualPercentage: number;
  minIndex: number;
  maxIndex: number;
  dndBlocked: boolean;
  fixedVolume: boolean;
  streamUnavailable?: boolean;
  failureReason?: string;
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
  executedImmediately?: boolean;
  message: string;
}

export interface SaveVolumeProfileParams {
  id?: string;
  name: string;
  icon: ProfileIconKey | string;
  startTime: string;
  endTime: string;
  streams: RuleStreamsMap;
  enabled: boolean;
  priority: number;
}

export interface SaveVolumeProfileResponse {
  success: boolean;
  profile: VolumeProfile;
  executedImmediately?: boolean;
  mode: string;
  profileOverlap: ProfileOverlapReport;
  activeProfileResolution: ProfileResolution;
  nextProfileTransitionAt: number;
  message: string;
}

export interface DeleteVolumeProfileResponse {
  success: boolean;
  deletedId: string;
  profiles: VolumeProfile[];
  profileOverlap: ProfileOverlapReport;
  activeProfileResolution: ProfileResolution;
  message: string;
}

export interface ToggleProfileResponse {
  success: boolean;
  profile: VolumeProfile;
  executedImmediately?: boolean;
  mode: string;
  profileOverlap: ProfileOverlapReport;
  activeProfileResolution: ProfileResolution;
  message: string;
}

export interface SetDefaultProfileResponse {
  success: boolean;
  defaultProfileId: string | null;
  activeProfileResolution: ProfileResolution;
  message: string;
}

export interface GetVolumeProfilesResponse {
  schedulerEnabled: boolean;
  profiles: VolumeProfile[];
  defaultProfileId: string | null;
  profileOverlap: ProfileOverlapReport;
  activeProfileResolution: ProfileResolution;
  nextProfileTransitionAt: number;
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
    executedImmediately?: boolean;
    message: string;
  }>;
  getVolumeProfiles(): Promise<GetVolumeProfilesResponse>;
  saveVolumeProfile(options: SaveVolumeProfileParams): Promise<SaveVolumeProfileResponse>;
  deleteVolumeProfile(options: { id: string }): Promise<DeleteVolumeProfileResponse>;
  toggleProfileEnabled(options: {
    id: string;
    enabled: boolean;
  }): Promise<ToggleProfileResponse>;
  setDefaultProfile(options: { profileId: string | null }): Promise<SetDefaultProfileResponse>;
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
