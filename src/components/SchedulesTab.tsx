import React from 'react';
import { CalendarClock, Edit3, Plus, Trash2 } from 'lucide-react';
import type { VolumeSchedule } from '../types/volume';
import {
  formatDaysSummary,
  formatTime12h,
  formatTimestamp,
  formatTriggerRelative,
  getStreamLabel,
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
            Executed natively by Android AlarmManager in local time, even when closed or rebooted.
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
            Add a daily or weekday schedule to adjust Ringtone, Notification, Alarm, Media, or Voice Call levels automatically.
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
            return (
              <div
                key={item.id}
                className={`bg-[#121A26] border rounded-2xl p-4 flex flex-col justify-between gap-4 transition-colors ${
                  item.enabled
                    ? 'border-slate-800/90'
                    : 'border-slate-800/50 opacity-75'
                }`}
              >
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="flex items-baseline gap-2.5 flex-wrap">
                      <span className="text-2xl font-bold font-mono-tabular text-slate-100">
                        {formatTime12h(item.time)}
                      </span>
                      <span className="text-base font-semibold font-mono-tabular text-cyan-300">
                        {item.percentage}%
                      </span>
                    </div>
                    <p className="text-sm font-medium text-slate-200 mt-1 truncate">
                      {getStreamLabel(item.stream)}
                      {item.label ? ` · ${item.label}` : ''}
                    </p>
                    <p className="text-xs text-slate-400 mt-1">
                      {formatDaysSummary(item.days)}
                    </p>
                  </div>

                  {/* Accessible native-style switch */}
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

                <div className="pt-3 border-t border-slate-800/80 flex items-center justify-between gap-2">
                  <div className="text-xs text-slate-400 min-w-0">
                    <div className="truncate font-mono-tabular">
                      {schedulerEnabled && item.enabled
                        ? formatTriggerRelative(item.nextTriggerAt)
                        : 'Not queued'}
                    </div>
                    {item.lastExecutedAt > 0 && (
                      <div
                        className={`truncate mt-0.5 ${
                          item.lastExecutionSuccess ? 'text-slate-500' : 'text-rose-400'
                        }`}
                      >
                        Last run: {formatTimestamp(item.lastExecutedAt)}
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
        <span className="font-semibold text-slate-200">Midnight-crossing behavior: </span>
        Each rule calculates its next local timestamp strictly ahead of the current clock and reschedules the following day or weekday immediately after firing—so overnight pairs like Night Mode at 8:00 PM and Day Mode at 8:00 AM transition seamlessly across midnight.
      </div>
    </div>
  );
};
