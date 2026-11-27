import React, { useEffect, useMemo, useState } from 'react';
import { AlertTriangle, X } from 'lucide-react';
import type {
  AudioStreamKey,
  RuleStreamsMap,
  SaveVolumeProfileParams,
  VolumeProfile,
} from '../types/volume';
import {
  PRIORITY_OPTIONS,
  PROFILE_ICON_OPTIONS,
  STREAM_OPTIONS,
  crossesMidnight,
  doProfileRangesOverlap,
  formatTime12h,
  formatTimeRange12h,
} from '../utils/formatters';
import { ProfileIcon } from './ProfileIcon';

interface ProfileFormSheetProps {
  open: boolean;
  initialProfile: VolumeProfile | null;
  existingProfiles: VolumeProfile[];
  saving: boolean;
  onClose: () => void;
  onSave: (params: SaveVolumeProfileParams) => Promise<void>;
}

function buildInitialStreams(source?: RuleStreamsMap): RuleStreamsMap {
  return {
    media: {
      enabled: source?.media?.enabled ?? true,
      percentage: source?.media?.percentage ?? 70,
    },
    ringtone: {
      enabled: source?.ringtone?.enabled ?? true,
      percentage: source?.ringtone?.percentage ?? 100,
    },
    notification: {
      enabled: source?.notification?.enabled ?? true,
      percentage: source?.notification?.percentage ?? 100,
    },
    alarm: {
      enabled: source?.alarm?.enabled ?? true,
      percentage: source?.alarm?.percentage ?? 80,
    },
    voice_call: {
      enabled: source?.voice_call?.enabled ?? true,
      percentage: source?.voice_call?.percentage ?? 100,
    },
  };
}

export const ProfileFormSheet: React.FC<ProfileFormSheetProps> = ({
  open,
  initialProfile,
  existingProfiles,
  saving,
  onClose,
  onSave,
}) => {
  const [name, setName] = useState('');
  const [icon, setIcon] = useState('sun');
  const [startTime, setStartTime] = useState('06:00');
  const [endTime, setEndTime] = useState('20:00');
  const [enabled, setEnabled] = useState(true);
  const [priority, setPriority] = useState(0);
  const [streams, setStreams] = useState<RuleStreamsMap>(() => buildInitialStreams());
  const [error, setError] = useState('');

  useEffect(() => {
    if (!open) return;
    if (initialProfile) {
      setName(initialProfile.name);
      setIcon(initialProfile.icon || 'sun');
      setStartTime(initialProfile.startTime);
      setEndTime(initialProfile.endTime);
      setEnabled(initialProfile.enabled);
      setPriority(initialProfile.priority ?? 0);
      setStreams(buildInitialStreams(initialProfile.streams));
    } else {
      setName('');
      setIcon('briefcase');
      setStartTime('09:00');
      setEndTime('17:00');
      setEnabled(true);
      setPriority(0);
      setStreams(buildInitialStreams());
    }
    setError('');
  }, [open, initialProfile]);

  const liveOverlaps = useMemo(() => {
    if (!enabled || !startTime || !endTime || startTime === endTime) return [];
    return existingProfiles
      .filter((p) => p.enabled && p.id !== initialProfile?.id)
      .filter((p) =>
        doProfileRangesOverlap({ startTime, endTime }, { startTime: p.startTime, endTime: p.endTime })
      )
      .map((p) => ({
        profile: p,
        samePriority: p.priority === priority,
      }));
  }, [enabled, startTime, endTime, priority, existingProfiles, initialProfile?.id]);

  if (!open) return null;

  const isMidnightSpan = crossesMidnight(startTime, endTime);

  const toggleStreamEnabled = (key: AudioStreamKey) => {
    setStreams((prev) => ({
      ...prev,
      [key]: {
        ...prev[key],
        enabled: !prev[key].enabled,
      },
    }));
  };

  const updateStreamPercentage = (key: AudioStreamKey, pct: number) => {
    const clamped = Math.min(100, Math.max(0, Math.round(pct)));
    setStreams((prev) => ({
      ...prev,
      [key]: {
        enabled: true,
        percentage: clamped,
      },
    }));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim()) {
      setError('Enter a profile name.');
      return;
    }
    if (!/^\d{2}:\d{2}$/.test(startTime) || !/^\d{2}:\d{2}$/.test(endTime)) {
      setError('Enter valid local start and end times.');
      return;
    }
    if (startTime === endTime) {
      setError('Start time and end time cannot be identical.');
      return;
    }
    const anyStreamEnabled = STREAM_OPTIONS.some((opt) => streams[opt.key]?.enabled);
    if (!anyStreamEnabled) {
      setError('Enable at least one audio stream for this profile.');
      return;
    }
    setError('');
    await onSave({
      id: initialProfile?.id,
      name: name.trim(),
      icon,
      startTime,
      endTime,
      streams,
      enabled,
      priority,
    });
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-end sm:items-center justify-center bg-black/70 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-labelledby="profile-sheet-title"
    >
      <div className="w-full sm:max-w-xl max-h-[92dvh] flex flex-col bg-[#121A26] border-t sm:border border-slate-800 rounded-t-3xl sm:rounded-2xl shadow-2xl overflow-hidden">
        <div className="sm:hidden w-10 h-1.5 bg-slate-700 rounded-full mx-auto mt-3 shrink-0" />

        <div className="flex items-center justify-between px-5 py-4 border-b border-slate-800/80 shrink-0">
          <div>
            <h2 id="profile-sheet-title" className="text-lg font-semibold text-slate-100">
              {initialProfile ? 'Edit Volume Profile' : 'New Volume Profile'}
            </h2>
            <p className="text-xs text-slate-400 mt-0.5">
              Active {formatTimeRange12h(startTime, endTime)}
              {isMidnightSpan ? ' · Crosses midnight' : ''}
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="min-h-[44px] min-w-[44px] flex items-center justify-center rounded-xl text-slate-400 hover:text-slate-100 hover:bg-slate-800/70 transition-colors"
            aria-label="Close profile editor"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="flex-1 overflow-y-auto px-5 py-5 space-y-5">
          {/* Profile Name & Icon */}
          <div className="space-y-4">
            <div>
              <label
                htmlFor="profile-name"
                className="block text-sm font-medium text-slate-300 mb-2"
              >
                Profile Name
              </label>
              <input
                id="profile-name"
                type="text"
                required
                maxLength={40}
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="e.g. Day Profile, Night Profile, Work"
                className="w-full min-h-[46px] px-3.5 py-2.5 rounded-xl bg-[#0B111A] border border-slate-700/80 text-slate-100 placeholder:text-slate-500 text-sm focus:outline-none focus:border-cyan-400"
              />
            </div>

            <div>
              <span className="block text-sm font-medium text-slate-300 mb-2">
                Profile Icon
              </span>
              <div className="grid grid-cols-4 gap-2">
                {PROFILE_ICON_OPTIONS.map((opt) => {
                  const active = icon === opt.key;
                  return (
                    <button
                      key={opt.key}
                      type="button"
                      onClick={() => setIcon(opt.key)}
                      className={`min-h-[44px] px-2.5 py-2 rounded-xl border text-xs font-medium flex items-center justify-center gap-1.5 transition-colors whitespace-nowrap shrink-0 ${
                        active
                          ? 'bg-cyan-400 text-[#0B111A] border-cyan-400 font-semibold'
                          : 'bg-[#0B111A] text-slate-300 border-slate-800 hover:border-slate-700'
                      }`}
                    >
                      <ProfileIcon icon={opt.key} className="w-3.5 h-3.5 shrink-0" />
                      <span>{opt.label}</span>
                    </button>
                  );
                })}
              </div>
            </div>
          </div>

          {/* Start Time & End Time */}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div>
              <label
                htmlFor="profile-start-time"
                className="block text-sm font-medium text-slate-300 mb-2"
              >
                Start Time
              </label>
              <input
                id="profile-start-time"
                type="time"
                required
                value={startTime}
                onChange={(e) => setStartTime(e.target.value)}
                className="w-full min-h-[46px] px-3.5 py-2.5 rounded-xl bg-[#0B111A] border border-slate-700/80 text-slate-100 font-mono-tabular text-base focus:outline-none focus:border-cyan-400"
              />
            </div>

            <div>
              <label
                htmlFor="profile-end-time"
                className="block text-sm font-medium text-slate-300 mb-2"
              >
                End Time
              </label>
              <input
                id="profile-end-time"
                type="time"
                required
                value={endTime}
                onChange={(e) => setEndTime(e.target.value)}
                className="w-full min-h-[46px] px-3.5 py-2.5 rounded-xl bg-[#0B111A] border border-slate-700/80 text-slate-100 font-mono-tabular text-base focus:outline-none focus:border-cyan-400"
              />
            </div>
          </div>

          {isMidnightSpan && (
            <p className="text-xs text-cyan-300 bg-cyan-500/10 border border-cyan-500/30 rounded-xl px-3.5 py-2.5">
              Midnight-crossing period: activates at {formatTime12h(startTime)}, continues past midnight, and ends at {formatTime12h(endTime)} the next day.
            </p>
          )}

          {/* Enable/Disable & Priority */}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div>
              <span className="block text-sm font-medium text-slate-300 mb-2">
                Profile Status
              </span>
              <button
                type="button"
                role="switch"
                aria-checked={enabled}
                onClick={() => setEnabled((v) => !v)}
                className={`w-full min-h-[46px] px-3.5 py-2.5 rounded-xl border text-sm font-medium flex items-center justify-between transition-colors ${
                  enabled
                    ? 'bg-cyan-500/10 border-cyan-400/50 text-cyan-300'
                    : 'bg-[#0B111A] border-slate-700/80 text-slate-400'
                }`}
              >
                <span>{enabled ? 'Enabled' : 'Disabled'}</span>
                <span className="font-mono-tabular text-xs">
                  {enabled ? 'ON' : 'OFF'}
                </span>
              </button>
            </div>

            <div>
              <label
                htmlFor="profile-priority"
                className="block text-sm font-medium text-slate-300 mb-2"
              >
                Overlap Priority
              </label>
              <select
                id="profile-priority"
                value={priority}
                onChange={(e) => setPriority(Number(e.target.value))}
                className="w-full min-h-[46px] px-3.5 py-2.5 rounded-xl bg-[#0B111A] border border-slate-700/80 text-slate-100 text-sm focus:outline-none focus:border-cyan-400"
              >
                {PRIORITY_OPTIONS.map((opt) => (
                  <option key={opt.value} value={opt.value}>
                    {opt.label}
                  </option>
                ))}
              </select>
            </div>
          </div>

          {/* Live Overlap Warning */}
          {liveOverlaps.length > 0 && (
            <div
              className={`rounded-xl border p-3.5 text-xs space-y-1.5 ${
                liveOverlaps.some((o) => o.samePriority)
                  ? 'bg-rose-500/10 border-rose-500/40 text-rose-200'
                  : 'bg-amber-500/10 border-amber-500/35 text-amber-200'
              }`}
            >
              <div className="flex items-center gap-2 font-semibold">
                <AlertTriangle className="w-4 h-4 shrink-0" />
                <span>
                  {liveOverlaps.some((o) => o.samePriority)
                    ? 'Ambiguous Time Overlap Detected'
                    : 'Time Overlap Resolved by Explicit Priority'}
                </span>
              </div>
              {liveOverlaps.map(({ profile, samePriority }) => (
                <p key={profile.id} className="leading-relaxed opacity-95">
                  {samePriority
                    ? `Overlaps with "${profile.name}" (${formatTimeRange12h(profile.startTime, profile.endTime)}) at equal priority (${priority}). Change the times or select a higher/lower Overlap Priority to avoid blocked activation.`
                    : `Overlaps with "${profile.name}" (${formatTimeRange12h(profile.startTime, profile.endTime)}, Priority ${profile.priority}). Priority ${Math.max(priority, profile.priority)} will take precedence.`}
                </p>
              ))}
            </div>
          )}

          {/* Independent Audio Stream Sliders */}
          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <span className="text-sm font-medium text-slate-300">
                Stream Volume Levels
              </span>
              <span className="text-xs text-slate-400">
                Independent per stream
              </span>
            </div>

            <div className="space-y-2.5">
              {STREAM_OPTIONS.map((opt) => {
                const cfg = streams[opt.key];
                const isStreamOn = cfg.enabled;
                return (
                  <div
                    key={opt.key}
                    className={`rounded-xl border p-3.5 transition-colors ${
                      isStreamOn
                        ? 'bg-[#0B111A] border-slate-700'
                        : 'bg-[#0B111A]/60 border-slate-800/80 opacity-75'
                    }`}
                  >
                    <div className="flex items-center justify-between gap-3">
                      <div className="min-w-0">
                        <div className="text-sm font-semibold text-slate-100">
                          {opt.label}
                        </div>
                        <div className="text-xs text-slate-400 truncate">
                          {isStreamOn ? opt.description : 'Left unchanged when profile starts'}
                        </div>
                      </div>

                      <div className="flex items-center gap-3 shrink-0">
                        <span
                          className={`font-mono-tabular text-sm font-semibold w-11 text-right ${
                            isStreamOn ? 'text-cyan-300' : 'text-slate-500'
                          }`}
                        >
                          {isStreamOn ? `${cfg.percentage}%` : 'Skip'}
                        </span>
                        <button
                          type="button"
                          role="switch"
                          aria-checked={isStreamOn}
                          aria-label={`Toggle ${opt.label} in profile`}
                          onClick={() => toggleStreamEnabled(opt.key)}
                          className="min-h-[44px] min-w-[48px] flex items-center justify-end"
                        >
                          <span
                            className={`w-11 h-6 rounded-full p-0.5 transition-colors flex items-center ${
                              isStreamOn ? 'bg-cyan-400 justify-end' : 'bg-slate-700 justify-start'
                            }`}
                          >
                            <span className="w-5 h-5 rounded-full bg-[#0B111A] shadow" />
                          </span>
                        </button>
                      </div>
                    </div>

                    <div className="mt-2.5 flex items-center gap-3">
                      <input
                        type="range"
                        min={0}
                        max={100}
                        step={1}
                        value={cfg.percentage}
                        aria-label={`${opt.label} volume percentage`}
                        onChange={(e) => updateStreamPercentage(opt.key, Number(e.target.value))}
                        className="volume-slider flex-1"
                      />
                    </div>
                  </div>
                );
              })}
            </div>
          </div>

          {error && (
            <p className="text-sm text-rose-400 bg-rose-500/10 border border-rose-500/30 rounded-xl px-3.5 py-2.5">
              {error}
            </p>
          )}

          <div className="pt-2 flex items-center gap-3 border-t border-slate-800/80">
            <button
              type="button"
              onClick={onClose}
              className="flex-1 min-h-[48px] px-4 py-2.5 rounded-xl border border-slate-700 text-slate-300 text-sm font-medium hover:bg-slate-800/60 transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={saving}
              className="flex-1 min-h-[48px] px-4 py-2.5 rounded-xl bg-cyan-400 text-[#0B111A] text-sm font-semibold hover:bg-cyan-300 disabled:opacity-60 transition-colors"
            >
              {saving ? 'Saving...' : initialProfile ? 'Save Profile' : 'Create Profile'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
