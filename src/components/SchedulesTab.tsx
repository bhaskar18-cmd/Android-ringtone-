import React from 'react';
import { CalendarClock, Edit3, Plus, Trash2 } from 'lucide-react';
import type { VolumeSchedule } from '../types/volume';
import {
  STREAM_OPTIONS,
  formatDaysSummary,
  formatTime12h,
  formatTimestamp,
  formatTriggerRelative,
} from '../utils/formatters';

interface SchedulesTabProps {
  schedules: VolumeSchedule[];
  schedulerEnabled: boolean;
  busyId: string | null;
  onAdd: () => void;
  onEdit: (schedule: VolumeSchedule) => void;
  onDelete: (id: string) => Promise<void>;
  onToggleRule: (id: string, enabled: boolean) => Promise<void>;
}

export const SchedulesTab: React.FC<SchedulesTabProps> = ({
  schedules,
  schedulerEnabled,
  busyId,
  onAdd,
  onEdit,
  onDelete,
  onToggleRule,
}) => {
  return (
    <div className="space-y-5">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold text-slate-100">
            Volume Schedules
          </h2>
          <p className="text-xs text-slate-400 mt-0.5">
            Each rule applies its enabled streams independently via native Android AlarmManager. Disabled streams stay untouched.
          </p>
        </div>

        <button
          type="button"
          onClick={onAdd}
          className="min-h-[44px] px-4 py-2 rounded-xl bg-cyan-400 text-[#0B111A] text-sm font-semibold hover:bg-cyan-300 transition-colors flex items-center justify-center gap-2 whitespace-nowrap shrink-0"
        >
          <Plus className="w-4 h-4" />
          <span>Add Schedule</span>
        </button>
      </div>

      {!schedulerEnabled && (
        <div className="bg-amber-500/10 border border-amber-500/30 rounded-2xl px-4 py-3 text-xs text-amber-200">
          Master scheduler is currently paused. Individual rules remain saved locally and will resume once you enable the scheduler.
        </div>
      )}

      {schedules.length === 0 ? (
        <div className="bg-[#121A26] border border-slate-800/90 rounded-2xl p-8 text-center space-y-3">
          <CalendarClock className="w-8 h-8 text-slate-500 mx-auto" />
          <p className="text-sm font-medium text-slate-200">No volume schedules configured</p>
          <p className="text-xs text-slate-400 max-w-sm mx-auto">
            Add a daily or weekday schedule to adjust Media, Ringtone, Notification, Alarm, or Voice Call levels automatically.
          </p>
          <button
            type="button"
            onClick={onAdd}
            className="min-h-[44px] px-5 py-2 rounded-xl bg-cyan-400 text-[#0B111A] text-sm font-semibold hover:bg-cyan-300 transition-colors"
          >
            Create First Schedule
          </button>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3.5">
          {schedules.map((item) => {
            const isBusy = busyId === item.id;
            const enabledStreams = STREAM_OPTIONS.filter(
              (opt) => item.streams[opt.key]?.enabled
            );
            return (
              <div
                key={item.id}
                className={`bg-[#121A26] border rounded-2xl p-4 flex flex-col justify-between gap-4 transition-colors ${
                  item.enabled
                    ? 'border-slate-800/90'
                    : 'border-slate-800/50 opacity-75'
                }`}
              >
                <div className="space-y-3">
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <div className="flex items-baseline gap-2.5 flex-wrap">
                        <span className="text-2xl font-bold font-mono-tabular text-slate-100">
                          {formatTime12h(item.time)}
                        </span>
                        {item.label && (
                          <span className="text-sm font-medium text-slate-300 truncate">
                            {item.label}
                          </span>
                        )}
                      </div>
                      <p className="text-xs text-slate-400 mt-1">
                        {formatDaysSummary(item.days)}
                      </p>
                    </div>

                    <button
                      type="button"
                      role="switch"
                      aria-checked={item.enabled}
                      aria-label={`Toggle ${item.label || item.time} schedule`}
                      disabled={isBusy}
                      onClick={() => void onToggleRule(item.id, !item.enabled)}
                      className="min-h-[44px] min-w-[52px] flex items-center justify-end shrink-0"
                    >
                      <span
                        className={`w-12 h-7 rounded-full p-1 transition-colors flex items-center ${
                          item.enabled ? 'bg-cyan-400 justify-end' : 'bg-slate-700 justify-start'
                        }`}
                      >
                        <span className="w-5 h-5 rounded-full bg-[#0B111A] shadow" />
                      </span>
                    </button>
                  </div>

                  {/* Per-stream targets list */}
                  <div className="grid grid-cols-2 gap-x-4 gap-y-1.5 pt-2 border-t border-slate-800/70 text-xs">
                    {enabledStreams.length === 0 ? (
                      <span className="text-slate-500 col-span-2">No streams enabled</span>
                    ) : (
                      enabledStreams.map((opt) => (
                        <div
                          key={opt.key}
                          className="flex items-center justify-between gap-2"
                        >
                          <span className="text-slate-400 truncate">{opt.label}</span>
                          <span className="font-mono-tabular font-semibold text-cyan-300">
                            {item.streams[opt.key].percentage}%
                          </span>
                        </div>
                      ))
                    )}
                  </div>
                </div>

                <div className="pt-3 border-t border-slate-800/80 flex items-center justify-between gap-2">
                  <div className="text-xs text-slate-400 min-w-0">
                    <div className="truncate font-mono-tabular">
                      {schedulerEnabled && item.enabled
                        ? formatTriggerRelative(item.nextTriggerAt)
                        : 'Not queued'}
                    </div>
                    {item.lastExecutedAt > 0 && (
                      <div
                        className={`mt-0.5 leading-snug break-words ${
                          item.lastExecutionSuccess ? 'text-slate-400' : 'text-rose-400'
                        }`}
                      >
                        Last run: {formatTimestamp(item.lastExecutedAt)}
                        {item.lastExecutionMessage ? ` · ${item.lastExecutionMessage}` : ''}
                      </div>
                    )}
                  </div>

                  <div className="flex items-center gap-1 shrink-0">
                    <button
                      type="button"
                      onClick={() => onEdit(item)}
                      disabled={isBusy}
                      className="min-h-[44px] min-w-[44px] flex items-center justify-center rounded-xl text-slate-300 hover:text-cyan-300 hover:bg-slate-800/70 transition-colors"
                      aria-label={`Edit schedule ${item.label || item.time}`}
                    >
                      <Edit3 className="w-4 h-4" />
                    </button>
                    <button
                      type="button"
                      onClick={() => void onDelete(item.id)}
                      disabled={isBusy}
                      className="min-h-[44px] min-w-[44px] flex items-center justify-center rounded-xl text-slate-400 hover:text-rose-400 hover:bg-rose-500/10 transition-colors"
                      aria-label={`Delete schedule ${item.label || item.time}`}
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

      <div className="bg-[#121A26] border border-slate-800/90 rounded-2xl p-4 text-xs text-slate-400 leading-relaxed">
        <span className="font-semibold text-slate-200">Persistent volume behavior: </span>
        Applied levels remain in place until another scheduled rule fires or you adjust device volume manually. Unchecked streams in a rule are left untouched.
      </div>
    </div>
  );
};
