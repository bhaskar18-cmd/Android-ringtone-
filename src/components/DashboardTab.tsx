import React, { useState } from 'react';
import {
  Bell,
  Clock,
  Moon,
  PauseCircle,
  PhoneCall,
  PlayCircle,
  RefreshCw,
  Sliders,
  Sun,
  Volume2,
} from 'lucide-react';
import type {
  AudioStreamKey,
  PermissionStatusResponse,
  VolumeApplyResult,
  VolumeSettingsResponse,
} from '../types/volume';
import {
  STREAM_OPTIONS,
  formatTime12h,
  formatTimestamp,
  formatTriggerRelative,
  getStreamLabel,
  inferActiveProfileName,
} from '../utils/formatters';

interface DashboardTabProps {
  settings: VolumeSettingsResponse;
  permissions: PermissionStatusResponse;
  busyAction: string | null;
  lastFeedback: { ok: boolean; text: string } | null;
  onRefresh: () => Promise<void>;
  onApplyVolume: (stream: AudioStreamKey, percentage: number) => Promise<VolumeApplyResult>;
  onApplyPreset: (mode: 'day' | 'night') => Promise<void>;
  onToggleScheduler: (enable: boolean) => Promise<void>;
  onGoToSchedules: () => void;
  onGoToPermissions: () => void;
}

export const DashboardTab: React.FC<DashboardTabProps> = ({
  settings,
  permissions,
  busyAction,
  lastFeedback,
  onRefresh,
  onApplyVolume,
  onApplyPreset,
  onToggleScheduler,
  onGoToSchedules,
  onGoToPermissions,
}) => {
  const [selectedStream, setSelectedStream] = useState<AudioStreamKey>('ringtone');
  const [sliderPct, setSliderPct] = useState<number>(
    settings.streams.ringtone?.percentage ?? 80
  );

  const activeProfile = inferActiveProfileName(
    settings.schedules,
    settings.schedulerEnabled
  );

  const handleStreamSelect = (stream: AudioStreamKey) => {
    setSelectedStream(stream);
    setSliderPct(settings.streams[stream]?.percentage ?? 70);
  };

  const ring = settings.streams.ringtone;
  const notif = settings.streams.notification;
  const call = settings.streams.voice_call;
  const alarm = settings.streams.alarm;
  const media = settings.streams.media;

  const missingCritical =
    settings.native &&
    (!permissions.exactAlarm.granted || !permissions.dndPolicyAccess.granted);

  return (
    <div className="space-y-6">
      {/* Top Status Overview */}
      <section className="grid grid-cols-1 lg:grid-cols-12 gap-4">
        <div className="lg:col-span-7 bg-[#121A26] border border-slate-800/90 rounded-2xl p-5 flex flex-col justify-between gap-4">
          <div className="flex items-start justify-between gap-3">
            <div>
              <p className="text-xs font-medium text-slate-400">
                Active Volume Profile
              </p>
              <h2 className="text-xl font-semibold text-slate-100 mt-1 leading-snug">
                {activeProfile}
              </h2>
              <p className="text-xs text-slate-400 mt-1.5">
                {settings.native
                  ? `${settings.deviceInfo?.manufacturer ?? 'Android'} ${settings.deviceInfo?.model ?? ''} · Android ${settings.deviceInfo?.androidVersion ?? ''} · Ringer: ${settings.ringerMode}`
                  : 'Web Preview · Connect Android APK for live AudioManager telemetry'}
              </p>
            </div>
            <button
              type="button"
              onClick={() => void onRefresh()}
              disabled={busyAction !== null}
              className="min-h-[44px] min-w-[44px] flex items-center justify-center rounded-xl bg-[#0B111A] border border-slate-800 text-slate-300 hover:text-cyan-300 hover:border-slate-700 transition-colors shrink-0"
              aria-label="Refresh system volume state"
            >
              <RefreshCw
                className={`w-4 h-4 ${busyAction === 'refresh' ? 'animate-spin' : ''}`}
              />
            </button>
          </div>

          <div className="pt-3 border-t border-slate-800/80 flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-2.5 text-sm">
              {settings.schedulerEnabled ? (
                <PlayCircle className="w-4 h-4 text-cyan-400 shrink-0" />
              ) : (
                <PauseCircle className="w-4 h-4 text-amber-400 shrink-0" />
              )}
              <span className="font-medium text-slate-200">
                Scheduler {settings.schedulerEnabled ? 'Active' : 'Disabled'}
              </span>
              <span className="text-slate-600" aria-hidden="true">
                ·
              </span>
              <span className="text-xs text-slate-400">
                {settings.native
                  ? permissions.exactAlarm.granted
                    ? 'Exact AlarmManager'
                    : 'Inexact fallback'
                  : 'Local preview'}
              </span>
            </div>

            <button
              type="button"
              onClick={() => void onToggleScheduler(!settings.schedulerEnabled)}
              disabled={busyAction !== null}
              className={`min-h-[44px] px-4 py-2 rounded-xl text-xs font-semibold transition-colors whitespace-nowrap shrink-0 ${
                settings.schedulerEnabled
                  ? 'bg-slate-800 text-slate-200 hover:bg-slate-700'
                  : 'bg-cyan-400 text-[#0B111A] hover:bg-cyan-300'
              }`}
            >
              {settings.schedulerEnabled ? 'Disable Scheduler' : 'Enable Scheduler'}
            </button>
          </div>
        </div>

        {/* Next Scheduled Change Card */}
        <div className="lg:col-span-5 bg-[#121A26] border border-slate-800/90 rounded-2xl p-5 flex flex-col justify-between gap-4">
          <div className="flex items-start justify-between gap-3">
            <div>
              <p className="text-xs font-medium text-slate-400">
                Next Scheduled Change
              </p>
              {settings.nextScheduledChange ? (
                <>
                  <div className="flex items-baseline gap-2.5 mt-1">
                    <span className="text-2xl font-bold font-mono-tabular text-slate-100">
                      {formatTime12h(settings.nextScheduledChange.time)}
                    </span>
                    <span className="text-sm font-semibold font-mono-tabular text-cyan-300">
                      {settings.nextScheduledChange.percentage}%
                    </span>
                  </div>
                  <p className="text-sm text-slate-300 mt-1">
                    {getStreamLabel(settings.nextScheduledChange.stream)}
                    {settings.nextScheduledChange.label
                      ? ` · ${settings.nextScheduledChange.label}`
                      : ''}
                  </p>
                </>
              ) : (
                <div className="mt-1.5">
                  <p className="text-base font-semibold text-slate-200">
                    No active alarm queued
                  </p>
                  <p className="text-xs text-slate-400 mt-1">
                    {settings.schedulerEnabled
                      ? 'Enable or add a schedule rule to queue an alarm.'
                      : 'Enable the scheduler to resume automatic triggers.'}
                  </p>
                </div>
              )}
            </div>
            <Clock className="w-5 h-5 text-cyan-400 shrink-0 mt-0.5" />
          </div>

          <div className="pt-3 border-t border-slate-800/80 flex items-center justify-between gap-2 text-xs">
            <span className="text-slate-400 font-mono-tabular">
              {settings.nextScheduledChange
                ? formatTriggerRelative(settings.nextScheduledChange.nextTriggerAt)
                : `Last boot sync: ${formatTimestamp(settings.bootRestore.lastBootRestoreAt)}`}
            </span>
            <button
              type="button"
              onClick={onGoToSchedules}
              className="text-cyan-400 hover:text-cyan-300 font-medium py-1.5 px-2 whitespace-nowrap shrink-0"
            >
              Manage rules
            </button>
          </div>
        </div>
      </section>

      {/* Permission Alert Banner when native permissions need attention */}
      {missingCritical && (
        <div className="bg-[#121A26] border border-amber-500/40 rounded-2xl p-4 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <div className="text-sm">
            <p className="font-medium text-amber-300">
              Android system permissions incomplete
            </p>
            <p className="text-xs text-slate-400 mt-0.5">
              {!permissions.exactAlarm.granted && 'Exact Alarm permission restricted (using inexact fallback). '}
              {!permissions.dndPolicyAccess.granted && 'DND Policy Access required to adjust Ringtone/Notification during Do Not Disturb.'}
            </p>
          </div>
          <button
            type="button"
            onClick={onGoToPermissions}
            className="min-h-[44px] px-4 py-2 rounded-xl bg-amber-400/15 border border-amber-400/40 text-amber-200 text-xs font-semibold hover:bg-amber-400/25 transition-colors whitespace-nowrap shrink-0"
          >
            Review Permissions
          </button>
        </div>
      )}

      {/* Primary Required System Stream Readouts */}
      <section>
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-sm font-semibold text-slate-200">
            Current System Volume Levels
          </h3>
          <span className="text-xs text-slate-400">
            Hardware step accuracy
          </span>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3.5">
          {[
            { snap: ring, icon: Volume2, subtitle: 'Incoming calls & ringer' },
            { snap: notif, icon: Bell, subtitle: 'System & app alerts' },
            { snap: call, icon: PhoneCall, subtitle: 'Voice & VoIP earpiece/speaker' },
          ].map(({ snap, icon: Icon, subtitle }) => (
            <div
              key={snap.stream}
              className="bg-[#121A26] border border-slate-800/90 rounded-2xl p-4 flex flex-col gap-3"
            >
              <div className="flex items-center justify-between gap-2">
                <div className="flex items-center gap-2.5 min-w-0">
                  <div className="w-9 h-9 rounded-xl bg-[#0B111A] border border-slate-800 flex items-center justify-center text-cyan-400 shrink-0">
                    <Icon className="w-4 h-4" />
                  </div>
                  <div className="min-w-0">
                    <p className="text-sm font-semibold text-slate-100 truncate">
                      {snap.label}
                    </p>
                    <p className="text-xs text-slate-400 truncate">{subtitle}</p>
                  </div>
                </div>
                <span className="text-lg font-bold font-mono-tabular text-slate-100 shrink-0">
                  {settings.native ? `${snap.percentage}%` : '--'}
                </span>
              </div>

              <div className="space-y-1.5">
                <div className="h-2 rounded-full bg-[#0B111A] overflow-hidden">
                  <div
                    className="h-full bg-cyan-400 rounded-full transition-all duration-200"
                    style={{ width: `${settings.native ? snap.percentage : 0}%` }}
                  />
                </div>
                <div className="flex items-center justify-between text-xs text-slate-400 font-mono-tabular">
                  <span>
                    {settings.native
                      ? `Step ${snap.currentIndex}/${snap.maxIndex}`
                      : 'Awaiting Android'}
                  </span>
                  <span>Min {snap.minIndex}</span>
                </div>
              </div>
            </div>
          ))}
        </div>

        {/* Secondary Streams (Alarm & Media) */}
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3.5 mt-3.5">
          {[alarm, media].map((snap) => (
            <div
              key={snap.stream}
              className="bg-[#121A26] border border-slate-800/90 rounded-2xl px-4 py-3.5 flex items-center justify-between gap-4"
            >
              <div className="min-w-0">
                <p className="text-sm font-medium text-slate-200">{snap.label}</p>
                <p className="text-xs text-slate-400 font-mono-tabular mt-0.5">
                  {settings.native
                    ? `Step ${snap.currentIndex} of ${snap.maxIndex}`
                    : 'Web preview'}
                </p>
              </div>
              <div className="flex items-center gap-3 shrink-0">
                <div className="w-24 h-1.5 rounded-full bg-[#0B111A] overflow-hidden">
                  <div
                    className="h-full bg-cyan-400/80 rounded-full"
                    style={{ width: `${settings.native ? snap.percentage : 0}%` }}
                  />
                </div>
                <span className="text-sm font-semibold font-mono-tabular text-slate-100 w-10 text-right">
                  {settings.native ? `${snap.percentage}%` : '--'}
                </span>
              </div>
            </div>
          ))}
        </div>
      </section>

      {/* Quick Controls & Apply Volume Now */}
      <section className="grid grid-cols-1 lg:grid-cols-12 gap-4">
        {/* Quick Mode Buttons */}
        <div className="lg:col-span-5 bg-[#121A26] border border-slate-800/90 rounded-2xl p-5 space-y-4">
          <div>
            <h3 className="text-sm font-semibold text-slate-100">Quick Controls</h3>
            <p className="text-xs text-slate-400 mt-0.5">
              Apply standard daytime or nighttime audio profiles immediately.
            </p>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <button
              type="button"
              onClick={() => void onApplyPreset('day')}
              disabled={busyAction !== null}
              className="min-h-[64px] p-3.5 rounded-xl bg-[#0B111A] border border-slate-800 hover:border-cyan-400/60 text-left flex flex-col justify-between gap-2 transition-colors disabled:opacity-60"
            >
              <div className="flex items-center justify-between">
                <Sun className="w-4 h-4 text-cyan-400" />
                <span className="text-xs font-mono-tabular text-slate-400">100%</span>
              </div>
              <div>
                <div className="text-sm font-semibold text-slate-100">Day Mode</div>
                <div className="text-xs text-slate-400">Ring & Notif 100%</div>
              </div>
            </button>

            <button
              type="button"
              onClick={() => void onApplyPreset('night')}
              disabled={busyAction !== null}
              className="min-h-[64px] p-3.5 rounded-xl bg-[#0B111A] border border-slate-800 hover:border-cyan-400/60 text-left flex flex-col justify-between gap-2 transition-colors disabled:opacity-60"
            >
              <div className="flex items-center justify-between">
                <Moon className="w-4 h-4 text-cyan-400" />
                <span className="text-xs font-mono-tabular text-slate-400">50%</span>
              </div>
              <div>
                <div className="text-sm font-semibold text-slate-100">Night Mode</div>
                <div className="text-xs text-slate-400">Ring & Notif 50%</div>
              </div>
            </button>
          </div>
        </div>

        {/* Apply Volume Now */}
        <div className="lg:col-span-7 bg-[#121A26] border border-slate-800/90 rounded-2xl p-5 space-y-4">
          <div className="flex items-center justify-between gap-2">
            <div className="flex items-center gap-2">
              <Sliders className="w-4 h-4 text-cyan-400" />
              <h3 className="text-sm font-semibold text-slate-100">
                Apply Volume Now
              </h3>
            </div>
            <span className="text-sm font-mono-tabular font-semibold text-cyan-300">
              {sliderPct}%
            </span>
          </div>

          <div className="flex flex-wrap gap-2">
            {STREAM_OPTIONS.map((opt) => {
              const active = selectedStream === opt.key;
              return (
                <button
                  key={opt.key}
                  type="button"
                  onClick={() => handleStreamSelect(opt.key)}
                  className={`min-h-[40px] px-3 py-1.5 rounded-lg text-xs font-medium border transition-colors whitespace-nowrap shrink-0 ${
                    active
                      ? 'bg-cyan-400 text-[#0B111A] border-cyan-400 font-semibold'
                      : 'bg-[#0B111A] text-slate-300 border-slate-800 hover:border-slate-700'
                  }`}
                >
                  {opt.label}
                </button>
              );
            })}
          </div>

          <input
            type="range"
            min={0}
            max={100}
            step={1}
            value={sliderPct}
            onChange={(e) => setSliderPct(Number(e.target.value))}
            aria-label="Target volume percentage"
            className="volume-slider w-full"
          />

          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
            <p className="text-xs text-slate-400">
              Android maps percentages to discrete hardware steps ({settings.streams[selectedStream]?.minIndex ?? 0}–{settings.streams[selectedStream]?.maxIndex ?? 15}).
            </p>
            <button
              type="button"
              onClick={() => void onApplyVolume(selectedStream, sliderPct)}
              disabled={busyAction !== null}
              className="min-h-[44px] px-5 py-2 rounded-xl bg-cyan-400 text-[#0B111A] text-sm font-semibold hover:bg-cyan-300 disabled:opacity-60 transition-colors whitespace-nowrap shrink-0"
            >
              {busyAction === 'apply' ? 'Applying...' : 'Apply Volume Now'}
            </button>
          </div>
        </div>
      </section>

      {lastFeedback && (
        <div
          className={`rounded-2xl border px-4 py-3 text-sm ${
            lastFeedback.ok
              ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-200'
              : 'bg-rose-500/10 border-rose-500/30 text-rose-200'
          }`}
        >
          {lastFeedback.text}
        </div>
      )}
    </div>
  );
};
