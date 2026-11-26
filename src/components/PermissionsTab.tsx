import React from 'react';
import {
  AlertTriangle,
  BatteryCharging,
  Bell,
  CheckCircle2,
  Clock,
  ExternalLink,
  RefreshCw,
  ShieldCheck,
  VolumeX,
} from 'lucide-react';
import type { PermissionActionType, PermissionStatusResponse } from '../types/volume';

interface PermissionsTabProps {
  permissions: PermissionStatusResponse;
  busyAction: string | null;
  lastMessage: string | null;
  onRefresh: () => Promise<void>;
  onRequestPermission: (type: PermissionActionType) => Promise<void>;
}

export const PermissionsTab: React.FC<PermissionsTabProps> = ({
  permissions,
  busyAction,
  lastMessage,
  onRefresh,
  onRequestPermission,
}) => {
  const rows: Array<{
    key: PermissionActionType;
    title: string;
    requirement: string;
    granted: boolean;
    detail: string;
    buttonLabel: string;
    icon: React.ComponentType<{ className?: string }>;
  }> = [
    {
      key: 'exact_alarm',
      title: 'Exact Alarm Scheduling',
      requirement: 'Required on Android 12–16 for minute-accurate firing',
      granted: permissions.exactAlarm.granted,
      detail: permissions.exactAlarm.detail,
      buttonLabel: 'Open Exact Alarm Settings',
      icon: Clock,
    },
    {
      key: 'dnd_access',
      title: 'Do Not Disturb Policy Access',
      requirement: 'Required to modify Ringtone & Notification across silent/DND states',
      granted: permissions.dndPolicyAccess.granted,
      detail: permissions.dndPolicyAccess.detail,
      buttonLabel: 'Open DND Policy Settings',
      icon: VolumeX,
    },
    {
      key: 'battery_optimization',
      title: 'Unrestricted Background Battery',
      requirement: 'Recommended on Vivo Funtouch / OriginOS',
      granted: permissions.batteryOptimizationExempt.granted,
      detail: permissions.batteryOptimizationExempt.detail,
      buttonLabel: 'Open Battery Settings',
      icon: BatteryCharging,
    },
    {
      key: 'notifications',
      title: 'Notification Permission (Android 13+)',
      requirement: 'Optional · Only used for silent test-alarm confirmation',
      granted: permissions.postNotifications.granted,
      detail: permissions.postNotifications.detail,
      buttonLabel: 'Grant Notifications',
      icon: Bell,
    },
  ];

  return (
    <div className="space-y-5">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold text-slate-100">
            Android System Permissions
          </h2>
          <p className="text-xs text-slate-400 mt-0.5">
            Minimal permissions verified live against Android 14–16 APIs. No accessibility, root, or background spy permissions are requested.
          </p>
        </div>

        <button
          type="button"
          onClick={() => void onRefresh()}
          disabled={busyAction !== null}
          className="min-h-[44px] px-4 py-2 rounded-xl bg-[#121A26] border border-slate-800 text-slate-200 text-xs font-medium hover:border-cyan-400/50 transition-colors flex items-center justify-center gap-2 whitespace-nowrap shrink-0"
        >
          <RefreshCw className={`w-3.5 h-3.5 ${busyAction === 'refresh' ? 'animate-spin' : ''}`} />
          <span>Recheck Status</span>
        </button>
      </div>

      {!permissions.native && (
        <div className="bg-[#121A26] border border-slate-800 rounded-2xl p-4 text-xs text-slate-300 leading-relaxed">
          Currently viewing in browser preview. Live OS checks for{' '}
          <span className="font-mono-tabular text-slate-100">AlarmManager.canScheduleExactAlarms()</span>,{' '}
          <span className="font-mono-tabular text-slate-100">NotificationManager.isNotificationPolicyAccessGranted</span>, and{' '}
          <span className="font-mono-tabular text-slate-100">PowerManager.isIgnoringBatteryOptimizations()</span> execute directly on your Vivo T5x 5G inside the Android APK.
        </div>
      )}

      {lastMessage && (
        <div className="bg-[#121A26] border border-cyan-400/40 rounded-2xl px-4 py-3 text-xs text-cyan-200">
          {lastMessage}
        </div>
      )}

      <div className="grid grid-cols-1 md:grid-cols-2 gap-3.5">
        {rows.map((row) => {
          const Icon = row.icon;
          const isGranted = permissions.native && row.granted;
          return (
            <div
              key={row.key}
              className="bg-[#121A26] border border-slate-800/90 rounded-2xl p-5 flex flex-col justify-between gap-4"
            >
              <div className="space-y-2.5">
                <div className="flex items-start justify-between gap-3">
                  <div className="flex items-center gap-2.5">
                    <div className="w-9 h-9 rounded-xl bg-[#0B111A] border border-slate-800 flex items-center justify-center text-cyan-400 shrink-0">
                      <Icon className="w-4 h-4" />
                    </div>
                    <div>
                      <h3 className="text-sm font-semibold text-slate-100">
                        {row.title}
                      </h3>
                      <p className="text-xs text-slate-400">{row.requirement}</p>
                    </div>
                  </div>
                </div>

                <div className="flex items-center gap-2 text-xs font-medium pt-1">
                  {isGranted ? (
                    <>
                      <CheckCircle2 className="w-4 h-4 text-emerald-400 shrink-0" />
                      <span className="text-emerald-300">Granted</span>
                    </>
                  ) : (
                    <>
                      <AlertTriangle className="w-4 h-4 text-amber-400 shrink-0" />
                      <span className="text-amber-300">
                        {permissions.native ? 'Not Granted' : 'Pending Android APK'}
                      </span>
                    </>
                  )}
                </div>

                <p className="text-xs text-slate-400 leading-relaxed">
                  {row.detail}
                </p>
              </div>

              <button
                type="button"
                disabled={busyAction !== null || isGranted}
                onClick={() => void onRequestPermission(row.key)}
                className={`min-h-[44px] px-4 py-2 rounded-xl text-xs font-semibold flex items-center justify-center gap-2 transition-colors ${
                  isGranted
                    ? 'bg-[#0B111A] border border-slate-800 text-slate-500 cursor-default'
                    : 'bg-cyan-400 text-[#0B111A] hover:bg-cyan-300'
                }`}
              >
                <span>{isGranted ? 'Active' : row.buttonLabel}</span>
                {!isGranted && <ExternalLink className="w-3.5 h-3.5" />}
              </button>
            </div>
          );
        })}
      </div>

      {/* Vivo Android 16 guidance */}
      <div className="bg-[#121A26] border border-slate-800/90 rounded-2xl p-5 space-y-2.5">
        <div className="flex items-center gap-2 text-sm font-semibold text-slate-100">
          <ShieldCheck className="w-4 h-4 text-cyan-400" />
          <span>Vivo T5x 5G (Android 16) Reliability Checklist</span>
        </div>
        <ul className="text-xs text-slate-400 space-y-1.5 list-disc pl-4 leading-relaxed">
          <li>
            Grant <span className="text-slate-200 font-medium">Alarms &amp; reminders</span> (Exact Alarm) so scheduled changes fire at the exact minute instead of falling back to Doze-batched inexact alarms.
          </li>
          <li>
            Set Battery Usage for <span className="text-slate-200 font-medium">Smart Volume Scheduler</span> to <span className="text-slate-200 font-medium">Unrestricted</span> (High background power consumption allowed) in Vivo App Info so Funtouch/OriginOS does not freeze <span className="font-mono-tabular">BootCompletedReceiver</span> after reboot.
          </li>
          <li>
            Grant <span className="text-slate-200 font-medium">Do Not Disturb access</span> if you want scheduled profiles to raise or lower Ringtone and Notification volumes while DND is active.
          </li>
        </ul>
      </div>
    </div>
  );
};
