import React from 'react';
import {
  AlertTriangle,
  CheckCircle2,
  Clock,
  Edit3,
  Plus,
  Trash2,
  XCircle,
} from 'lucide-react';
import type {
  ProfileOverlapReport,
  ProfileResolution,
  VolumeProfile,
  VolumeSchedule,
} from '../types/volume';
import {
  STREAM_OPTIONS,
  crossesMidnight,
  formatTimeRange12h,
  formatTimestamp,
  formatTriggerRelative,
} from '../utils/formatters';
import { ProfileIcon } from './ProfileIcon';
import { SchedulesTab } from './SchedulesTab';

interface ProfilesTabProps {
  profiles: VolumeProfile[];
  defaultProfileId: string | null;
  profileOverlap?: ProfileOverlapReport;
  activeResolution?: ProfileResolution;
  nextTransitionAt: number;
  schedulerEnabled: boolean;
  busyProfileId: string | null;
  schedules: VolumeSchedule[];
  busyScheduleId: string | null;
  lastFeedback: { ok: boolean; text: string } | null;
  onAddProfile: () => void;
  onEditProfile: (profile: VolumeProfile) => void;
  onDeleteProfile: (id: string) => Promise<void>;
  onToggleProfile: (id: string, enabled: boolean) => Promise<void>;
  onChangeDefaultProfile: (profileId: string | null) => Promise<void>;
  onAddSchedule: () => void;
  onEditSchedule: (schedule: VolumeSchedule) => void;
  onDeleteSchedule: (id: string) => Promise<void>;
  onToggleSchedule: (id: string, enabled: boolean) => Promise<void>;
}

export const ProfilesTab: React.FC<ProfilesTabProps> = ({
  profiles,
  defaultProfileId,
  profileOverlap,
  activeResolution,
  nextTransitionAt,
  schedulerEnabled,
  busyProfileId,
  schedules,
  busyScheduleId,
  lastFeedback,
  onAddProfile,
  onEditProfile,
  onDeleteProfile,
  onToggleProfile,
  onChangeDefaultProfile,
  onAddSchedule,
  onEditSchedule,
  onDeleteSchedule,
  onToggleSchedule,
}) => {
  const activeId = activeResolution?.activeProfile?.id ?? null;
  const enabledProfiles = profiles.filter((p) => p.enabled);

  return (
    <div className="space-y-8">
      {/* Volume Profiles Primary Section */}
      <section className="space-y-5">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <div>
            <h2 className="text-lg font-semibold text-slate-100">
              Time-Based Volume Profiles
            </h2>
            <p className="text-xs text-slate-400 mt-0.5">
              Profiles switch automatically at start and end times via native Android AlarmManager, including overnight periods crossing midnight.
            </p>
          </div>

          <button
            type="button"
            onClick={onAddProfile}
            className="min-h-[44px] px-4 py-2 rounded-xl bg-cyan-400 text-[#0B111A] text-sm font-semibold hover:bg-cyan-300 transition-colors flex items-center justify-center gap-2 whitespace-nowrap shrink-0"
          >
            <Plus className="w-4 h-4" />
            <span>Add Profile</span>
          </button>
        </div>

        {lastFeedback && (
          <div
            className={`rounded-2xl border px-4 py-3 text-xs ${
              lastFeedback.ok
                ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-200'
                : 'bg-rose-500/10 border-rose-500/30 text-rose-200'
            }`}
          >
            {lastFeedback.text}
          </div>
        )}

        {/* Active Profile Resolution Status */}
        {activeResolution && (
          <div
            className={`rounded-2xl border p-4 flex flex-col sm:flex-row sm:items-center justify-between gap-3 ${
              activeResolution.status === 'conflict'
                ? 'bg-rose-500/10 border-rose-500/40 text-rose-100'
                : activeResolution.status === 'active' || activeResolution.status === 'fallback'
                  ? 'bg-[#121A26] border-slate-800/90 text-slate-100'
                  : 'bg-[#121A26] border-amber-500/35 text-amber-100'
            }`}
          >
            <div className="flex items-start gap-3 min-w-0">
              {activeResolution.status === 'conflict' ? (
                <XCircle className="w-5 h-5 text-rose-400 shrink-0 mt-0.5" />
              ) : activeResolution.activeProfile ? (
                <CheckCircle2 className="w-5 h-5 text-emerald-400 shrink-0 mt-0.5" />
              ) : (
                <AlertTriangle className="w-5 h-5 text-amber-400 shrink-0 mt-0.5" />
              )}
              <div className="min-w-0 space-y-0.5 text-xs">
                <p className="text-sm font-semibold">
                  {activeResolution.status === 'active' && activeResolution.activeProfile
                    ? `Active now: ${activeResolution.activeProfile.name}`
                    : activeResolution.status === 'fallback' && activeResolution.activeProfile
                      ? `Fallback active: ${activeResolution.activeProfile.name}`
                      : activeResolution.status === 'conflict'
                        ? 'Profile Overlap Conflict — Activation Paused'
                        : activeResolution.status === 'paused'
                          ? 'Scheduler Paused'
                          : 'No profile active'}
                </p>
                <p className="text-slate-400 leading-relaxed">
                  {activeResolution.message}
                </p>
              </div>
            </div>

            {schedulerEnabled && nextTransitionAt > 0 && (
              <div className="text-xs font-mono-tabular text-cyan-300 whitespace-nowrap shrink-0">
                Next switch: {formatTriggerRelative(nextTransitionAt)}
              </div>
            )}
          </div>
        )}

        {/* Overlap Warnings Banner */}
        {profileOverlap && profileOverlap.hasAnyOverlap && (
          <div
            className={`rounded-2xl border p-4 text-xs space-y-1.5 ${
              profileOverlap.hasAmbiguousOverlap
                ? 'bg-rose-500/10 border-rose-500/40 text-rose-200'
                : 'bg-amber-500/10 border-amber-500/35 text-amber-200'
            }`}
          >
            <div className="flex items-center gap-2 font-semibold text-sm">
              <AlertTriangle className="w-4 h-4 shrink-0" />
              <span>
                {profileOverlap.hasAmbiguousOverlap
                  ? 'Overlapping Profiles Require Resolution'
                  : 'Overlapping Time Ranges (Priority Active)'}
              </span>
            </div>
            {profileOverlap.warnings.map((w, idx) => (
              <p key={idx} className="leading-relaxed">
                {w}
              </p>
            ))}
          </div>
        )}

        {/* Profiles List */}
        {profiles.length === 0 ? (
          <div className="bg-[#121A26] border border-slate-800/90 rounded-2xl p-8 text-center space-y-3">
            <Clock className="w-8 h-8 text-slate-500 mx-auto" />
            <p className="text-sm font-medium text-slate-200">
              No volume profiles configured
            </p>
            <p className="text-xs text-slate-400 max-w-sm mx-auto">
              Create Day and Night time-based profiles with independent percentages for Media, Ringtone, Notification, Alarm, and Voice Call.
            </p>
            <button
              type="button"
              onClick={onAddProfile}
              className="min-h-[44px] px-5 py-2 rounded-xl bg-cyan-400 text-[#0B111A] text-sm font-semibold hover:bg-cyan-300 transition-colors"
            >
              Create First Profile
            </button>
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3.5">
            {profiles.map((profile) => {
              const isBusy = busyProfileId === profile.id;
              const isActiveNow = activeId === profile.id;
              const isAmbiguous =
                profileOverlap?.ambiguousProfileIds.includes(profile.id) ?? false;
              const isMidnight = crossesMidnight(profile.startTime, profile.endTime);
              const enabledStreams = STREAM_OPTIONS.filter(
                (opt) => profile.streams[opt.key]?.enabled
              );

              return (
                <div
                  key={profile.id}
                  className={`bg-[#121A26] border rounded-2xl p-4 flex flex-col justify-between gap-4 transition-colors ${
                    isAmbiguous
                      ? 'border-rose-500/50'
                      : isActiveNow
                        ? 'border-cyan-400/60'
                        : profile.enabled
                          ? 'border-slate-800/90'
                          : 'border-slate-800/50 opacity-75'
                  }`}
                >
                  <div className="space-y-3">
                    <div className="flex items-start justify-between gap-3">
                      <div className="flex items-start gap-3 min-w-0">
                        <div
                          className={`w-10 h-10 rounded-xl border flex items-center justify-center shrink-0 ${
                            isActiveNow
                              ? 'bg-cyan-400/15 border-cyan-400/40 text-cyan-300'
                              : 'bg-[#0B111A] border-slate-800 text-cyan-400'
                          }`}
                        >
                          <ProfileIcon icon={profile.icon} className="w-5 h-5" />
                        </div>

                        <div className="min-w-0">
                          <h3 className="text-base font-semibold text-slate-100 truncate">
                            {profile.name}
                          </h3>
                          <div className="text-xs font-mono-tabular text-slate-300 mt-0.5">
                            {formatTimeRange12h(profile.startTime, profile.endTime)}
                          </div>
                          <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5 text-[11px] text-slate-400 mt-1">
                            <span>
                              {isActiveNow
                                ? activeResolution?.isFallback
                                  ? 'Active (Fallback)'
                                  : 'Active now'
                                : profile.enabled
                                  ? 'Enabled'
                                  : 'Disabled'}
                            </span>
                            {isMidnight && (
                              <>
                                <span aria-hidden="true">·</span>
                                <span className="text-cyan-300">Crosses midnight</span>
                              </>
                            )}
                            {profile.priority > 0 && (
                              <>
                                <span aria-hidden="true">·</span>
                                <span>Priority {profile.priority}</span>
                              </>
                            )}
                            {defaultProfileId === profile.id && (
                              <>
                                <span aria-hidden="true">·</span>
                                <span className="text-slate-200">Fallback Default</span>
                              </>
                            )}
                          </div>
                        </div>
                      </div>

                      <button
                        type="button"
                        role="switch"
                        aria-checked={profile.enabled}
                        aria-label={`Toggle ${profile.name} profile`}
                        disabled={isBusy}
                        onClick={() => void onToggleProfile(profile.id, !profile.enabled)}
                        className="min-h-[44px] min-w-[52px] flex items-center justify-end shrink-0"
                      >
                        <span
                          className={`w-12 h-7 rounded-full p-1 transition-colors flex items-center ${
                            profile.enabled ? 'bg-cyan-400 justify-end' : 'bg-slate-700 justify-start'
                          }`}
                        >
                          <span className="w-5 h-5 rounded-full bg-[#0B111A] shadow" />
                        </span>
                      </button>
                    </div>

                    {/* All enabled streams & percentages */}
                    <div className="grid grid-cols-2 gap-x-4 gap-y-1.5 pt-2.5 border-t border-slate-800/70 text-xs">
                      {enabledStreams.map((opt) => (
                        <div
                          key={opt.key}
                          className="flex items-center justify-between gap-2"
                        >
                          <span className="text-slate-400 truncate">{opt.label}</span>
                          <span className="font-mono-tabular font-semibold text-cyan-300">
                            {profile.streams[opt.key].percentage}%
                          </span>
                        </div>
                      ))}
                    </div>
                  </div>

                  <div className="pt-3 border-t border-slate-800/80 flex items-center justify-between gap-2">
                    <div className="text-xs text-slate-400 min-w-0">
                      <div className="truncate font-mono-tabular">
                        {schedulerEnabled && profile.enabled && profile.nextTransitionAt > 0
                          ? `Starts ${formatTriggerRelative(profile.nextTransitionAt)}`
                          : 'Not queued'}
                      </div>
                      {profile.lastActivatedAt > 0 && (
                        <div
                          className={`mt-0.5 leading-snug break-words ${
                            profile.lastExecutionSuccess ? 'text-slate-400' : 'text-rose-400'
                          }`}
                        >
                          Last applied: {formatTimestamp(profile.lastActivatedAt)}
                          {profile.lastExecutionMessage ? ` · ${profile.lastExecutionMessage}` : ''}
                        </div>
                      )}
                    </div>

                    <div className="flex items-center gap-1 shrink-0">
                      <button
                        type="button"
                        onClick={() => onEditProfile(profile)}
                        disabled={isBusy}
                        className="min-h-[44px] min-w-[44px] flex items-center justify-center rounded-xl text-slate-300 hover:text-cyan-300 hover:bg-slate-800/70 transition-colors"
                        aria-label={`Edit profile ${profile.name}`}
                      >
                        <Edit3 className="w-4 h-4" />
                      </button>
                      <button
                        type="button"
                        onClick={() => void onDeleteProfile(profile.id)}
                        disabled={isBusy}
                        className="min-h-[44px] min-w-[44px] flex items-center justify-center rounded-xl text-slate-400 hover:text-rose-400 hover:bg-rose-500/10 transition-colors"
                        aria-label={`Delete profile ${profile.name}`}
                      >
                        <Trash2 className="w-4 h-4" />
                      </button>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        )}

        {/* Default Fallback Profile Control */}
        <div className="bg-[#121A26] border border-slate-800/90 rounded-2xl p-4 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <div className="text-xs space-y-0.5">
            <p className="text-sm font-semibold text-slate-100">
              Fallback Profile (Outside Timed Windows)
            </p>
            <p className="text-slate-400 leading-relaxed">
              When no enabled profile covers the current time, keep current device volume unchanged unless you pick a default profile.
            </p>
          </div>
          <select
            aria-label="Fallback default profile"
            value={defaultProfileId ?? ''}
            onChange={(e) =>
              void onChangeDefaultProfile(e.target.value ? e.target.value : null)
            }
            className="min-h-[44px] px-3.5 py-2 rounded-xl bg-[#0B111A] border border-slate-800 text-xs font-medium text-slate-100 focus:outline-none focus:border-cyan-400 shrink-0"
          >
            <option value="">None (Keep current volume unchanged)</option>
            {enabledProfiles.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name} ({formatTimeRange12h(p.startTime, p.endTime)})
              </option>
            ))}
          </select>
        </div>
      </section>

      {/* Preserved Individual Point-in-Time Schedules */}
      <section className="pt-4 border-t border-slate-800/80">
        <SchedulesTab
          schedules={schedules}
          schedulerEnabled={schedulerEnabled}
          busyId={busyScheduleId}
          onAdd={onAddSchedule}
          onEdit={onEditSchedule}
          onDelete={onDeleteSchedule}
          onToggleRule={onToggleSchedule}
        />
      </section>
    </div>
  );
};
