import React, { useEffect, useState } from 'react';
import { X } from 'lucide-react';
import type { AudioStreamKey, ScheduleVolumeChangeParams, VolumeSchedule } from '../types/volume';
import { STREAM_OPTIONS, WEEKDAY_LABELS, formatTime12h } from '../utils/formatters';

interface ScheduleFormSheetProps {
  open: boolean;
  initialSchedule: VolumeSchedule | null;
  saving: boolean;
  onClose: () => void;
  onSave: (params: ScheduleVolumeChangeParams) => Promise<void>;
}

export const ScheduleFormSheet: React.FC<ScheduleFormSheetProps> = ({
  open,
  initialSchedule,
  saving,
  onClose,
  onSave,
}) => {
  const [label, setLabel] = useState('');
  const [time, setTime] = useState('08:00');
  const [stream, setStream] = useState<AudioStreamKey>('ringtone');
  const [percentage, setPercentage] = useState(80);
  const [days, setDays] = useState<number[]>([0, 1, 2, 3, 4, 5, 6]);
  const [enabled, setEnabled] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!open) return;
    if (initialSchedule) {
      setLabel(initialSchedule.label);
      setTime(initialSchedule.time);
      setStream(initialSchedule.stream);
      setPercentage(initialSchedule.percentage);
      setDays(initialSchedule.days.length > 0 ? initialSchedule.days : [0, 1, 2, 3, 4, 5, 6]);
      setEnabled(initialSchedule.enabled);
    } else {
      setLabel('');
      setTime('08:00');
      setStream('ringtone');
      setPercentage(80);
      setDays([0, 1, 2, 3, 4, 5, 6]);
      setEnabled(true);
    }
    setError('');
  }, [open, initialSchedule]);

  if (!open) return null;

  const toggleDay = (dayIndex: number) => {
    setDays((prev) => {
      if (prev.includes(dayIndex)) {
        if (prev.length === 1) return prev; // keep at least 1 day selected
        return prev.filter((d) => d !== dayIndex);
      }
      return [...prev, dayIndex].sort((a, b) => a - b);
    });
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!/^\d{2}:\d{2}$/.test(time)) {
      setError('Enter a valid local time in HH:mm format.');
      return;
    }
    if (days.length === 0) {
      setError('Select at least one weekday.');
      return;
    }
    setError('');
    await onSave({
      id: initialSchedule?.id,
      label: label.trim(),
      time,
      stream,
      percentage,
      days,
      enabled,
    });
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-end sm:items-center justify-center bg-black/70 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-labelledby="schedule-sheet-title"
    >
      <div className="w-full sm:max-w-lg max-h-[92dvh] flex flex-col bg-[#121A26] border-t sm:border border-slate-800 rounded-t-3xl sm:rounded-2xl shadow-2xl overflow-hidden">
        <div className="sm:hidden w-10 h-1.5 bg-slate-700 rounded-full mx-auto mt-3 shrink-0" />

        <div className="flex items-center justify-between px-5 py-4 border-b border-slate-800/80 shrink-0">
          <div>
            <h2 id="schedule-sheet-title" className="text-lg font-semibold text-slate-100">
              {initialSchedule ? 'Edit Schedule' : 'New Volume Schedule'}
            </h2>
            <p className="text-xs text-slate-400 mt-0.5">
              Triggers automatically at {formatTime12h(time)} local time
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="min-h-[44px] min-w-[44px] flex items-center justify-center rounded-xl text-slate-400 hover:text-slate-100 hover:bg-slate-800/70 transition-colors"
            aria-label="Close schedule editor"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="flex-1 overflow-y-auto px-5 py-5 space-y-5">
          <div>
            <label
              htmlFor="schedule-label"
              className="block text-sm font-medium text-slate-300 mb-2"
            >
              Profile Label
            </label>
            <input
              id="schedule-label"
              type="text"
              maxLength={40}
              value={label}
              onChange={(e) => setLabel(e.target.value)}
              placeholder="e.g. Morning Ringtone or Night Quiet"
              className="w-full min-h-[46px] px-3.5 py-2.5 rounded-xl bg-[#0B111A] border border-slate-700/80 text-slate-100 placeholder:text-slate-500 text-sm focus:outline-none focus:border-cyan-400"
            />
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div>
              <label
                htmlFor="schedule-time"
                className="block text-sm font-medium text-slate-300 mb-2"
              >
                Trigger Time (Local)
              </label>
              <input
                id="schedule-time"
                type="time"
                required
                value={time}
                onChange={(e) => setTime(e.target.value)}
                className="w-full min-h-[46px] px-3.5 py-2.5 rounded-xl bg-[#0B111A] border border-slate-700/80 text-slate-100 font-mono-tabular text-base focus:outline-none focus:border-cyan-400"
              />
            </div>

            <div>
              <span className="block text-sm font-medium text-slate-300 mb-2">
                Rule State
              </span>
              <button
                type="button"
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
          </div>

          <div>
            <span className="block text-sm font-medium text-slate-300 mb-2">
              Android Audio Stream
            </span>
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
              {STREAM_OPTIONS.map((opt) => {
                const active = stream === opt.key;
                return (
                  <button
                    key={opt.key}
                    type="button"
                    onClick={() => setStream(opt.key)}
                    className={`min-h-[44px] px-3 py-2 rounded-xl border text-left transition-colors ${
                      active
                        ? 'bg-cyan-500/15 border-cyan-400 text-cyan-200'
                        : 'bg-[#0B111A] border-slate-800 text-slate-300 hover:border-slate-700'
                    }`}
                  >
                    <div className="text-sm font-medium whitespace-nowrap">{opt.label}</div>
                  </button>
                );
              })}
            </div>
            {stream === 'voice_call' && (
              <p className="text-xs text-slate-400 mt-2">
                Android enforces a minimum non-zero step on Voice Call stream on most devices.
              </p>
            )}
          </div>

          <div>
            <div className="flex items-center justify-between mb-2">
              <label
                htmlFor="schedule-percentage"
                className="text-sm font-medium text-slate-300"
              >
                Target Volume Level
              </label>
              <span className="font-mono-tabular text-base font-semibold text-cyan-300">
                {percentage}%
              </span>
            </div>
            <input
              id="schedule-percentage"
              type="range"
              min={0}
              max={100}
              step={1}
              value={percentage}
              onChange={(e) => setPercentage(Number(e.target.value))}
              className="volume-slider w-full"
            />
            <div className="grid grid-cols-5 gap-2 mt-3">
              {[0, 25, 50, 75, 100].map((preset) => (
                <button
                  key={preset}
                  type="button"
                  onClick={() => setPercentage(preset)}
                  className={`min-h-[40px] rounded-lg text-xs font-mono-tabular font-medium border transition-colors ${
                    percentage === preset
                      ? 'bg-cyan-400 text-[#0B111A] border-cyan-400'
                      : 'bg-[#0B111A] text-slate-300 border-slate-800 hover:border-slate-700'
                  }`}
                >
                  {preset}%
                </button>
              ))}
            </div>
          </div>

          <div>
            <div className="flex items-center justify-between mb-2">
              <span className="text-sm font-medium text-slate-300">Repeat Weekdays</span>
              <div className="flex items-center gap-3 text-xs">
                <button
                  type="button"
                  onClick={() => setDays([0, 1, 2, 3, 4, 5, 6])}
                  className="text-cyan-400 hover:text-cyan-300 py-1 px-1"
                >
                  Every day
                </button>
                <button
                  type="button"
                  onClick={() => setDays([1, 2, 3, 4, 5])}
                  className="text-slate-400 hover:text-slate-200 py-1 px-1"
                >
                  Weekdays
                </button>
              </div>
            </div>
            <div className="grid grid-cols-7 gap-1.5">
              {WEEKDAY_LABELS.map((dayName, idx) => {
                const selected = days.includes(idx);
                return (
                  <button
                    key={dayName}
                    type="button"
                    onClick={() => toggleDay(idx)}
                    className={`min-h-[44px] rounded-xl text-xs font-medium border transition-colors ${
                      selected
                        ? 'bg-cyan-400 text-[#0B111A] border-cyan-400 font-semibold'
                        : 'bg-[#0B111A] text-slate-400 border-slate-800 hover:border-slate-700'
                    }`}
                    aria-pressed={selected}
                  >
                    {dayName}
                  </button>
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
              {saving ? 'Saving...' : initialSchedule ? 'Save Changes' : 'Save Schedule'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
