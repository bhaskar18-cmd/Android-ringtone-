import { useCallback, useEffect, useState } from 'react';
import {
  Activity,
  CalendarClock,
  FlaskConical,
  Plus,
  ShieldCheck,
  Volume2,
} from 'lucide-react';
import { DashboardTab } from './components/DashboardTab';
import { PermissionsTab } from './components/PermissionsTab';
import { ScheduleFormSheet } from './components/ScheduleFormSheet';
import { SchedulesTab } from './components/SchedulesTab';
import { TestingTab } from './components/TestingTab';
import { volumeScheduler } from './native/volumeScheduler';
import type {
  AudioStreamKey,
  PermissionActionType,
  PermissionStatusResponse,
  ScheduleVolumeChangeParams,
  VolumeSchedule,
  VolumeSettingsResponse,
} from './types/volume';

type AppTab = 'dashboard' | 'schedules' | 'testing' | 'permissions';

export default function App() {
  const [activeTab, setActiveTab] = useState<AppTab>('dashboard');
  const [settings, setSettings] = useState<VolumeSettingsResponse | null>(null);
  const [permissions, setPermissions] = useState<PermissionStatusResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [busyAction, setBusyAction] = useState<string | null>(null);
  const [busyScheduleId, setBusyScheduleId] = useState<string | null>(null);
  const [feedback, setFeedback] = useState<{ ok: boolean; text: string } | null>(null);
  const [permMessage, setPermMessage] = useState<string | null>(null);

  const [sheetOpen, setSheetOpen] = useState(false);
  const [editingSchedule, setEditingSchedule] = useState<VolumeSchedule | null>(null);
  const [savingSchedule, setSavingSchedule] = useState(false);

  const loadAll = useCallback(async (showSpinner = false) => {
    if (showSpinner) setBusyAction('refresh');
    try {
      const [volSettings, permStatus] = await Promise.all([
        volumeScheduler.getVolumeSettings(),
        volumeScheduler.getPermissionStatus(),
      ]);
      setSettings(volSettings);
      setPermissions(permStatus);
    } catch (err) {
      setFeedback({
        ok: false,
        text: err instanceof Error ? err.message : 'Failed to read volume scheduler state.',
      });
    } finally {
      setLoading(false);
      setBusyAction(null);
    }
  }, []);

  useEffect(() => {
    void loadAll();
    let unsub: (() => void) | undefined;
    void volumeScheduler.subscribeToNativeUpdates((updated) => {
      setSettings(updated);
    }).then((fn) => {
      unsub = fn;
    });

    const handleResume = () => {
      if (document.visibilityState === 'visible') {
        void loadAll();
      }
    };
    window.addEventListener('focus', handleResume);
    document.addEventListener('visibilitychange', handleResume);

    return () => {
      unsub?.();
      window.removeEventListener('focus', handleResume);
      document.removeEventListener('visibilitychange', handleResume);
    };
  }, [loadAll]);

  const handleApplyVolume = async (stream: AudioStreamKey, percentage: number) => {
    setBusyAction('apply');
    try {
      const res = await volumeScheduler.setVolume(stream, percentage);
      setFeedback({ ok: res.success, text: res.message });
      await loadAll();
      return res;
    } finally {
      setBusyAction(null);
    }
  };

  const handleApplyPreset = async (mode: 'day' | 'night') => {
    setBusyAction('preset');
    try {
      const ringPct = mode === 'day' ? 100 : 50;
      const notifPct = mode === 'day' ? 100 : 50;
      const mediaPct = mode === 'day' ? 80 : 35;

      const r1 = await volumeScheduler.setVolume('ringtone', ringPct);
      const r2 = await volumeScheduler.setVolume('notification', notifPct);
      const r3 = await volumeScheduler.setVolume('media', mediaPct);

      const allOk = r1.success && r2.success && r3.success;
      if (allOk) {
        setFeedback({
          ok: true,
          text: `${mode === 'day' ? 'Day Mode' : 'Night Mode'} applied: Ringtone ${r1.actualIndex}/${r1.maxIndex} (${r1.actualPercentage}%), Notification ${r2.actualIndex}/${r2.maxIndex} (${r2.actualPercentage}%), Media ${r3.actualIndex}/${r3.maxIndex} (${r3.actualPercentage}%).`,
        });
      } else {
        const firstErr = [r1, r2, r3].find((r) => !r.success);
        setFeedback({
          ok: false,
          text: firstErr?.message ?? 'Failed to apply preset.',
        });
      }
      await loadAll();
    } finally {
      setBusyAction(null);
    }
  };

  const handleToggleScheduler = async (enable: boolean) => {
    setBusyAction('toggle-scheduler');
    try {
      const res = enable
        ? await volumeScheduler.enableScheduler()
        : await volumeScheduler.disableScheduler();
      setFeedback({ ok: res.success, text: res.message });
      await loadAll();
    } finally {
      setBusyAction(null);
    }
  };

  const handleSaveSchedule = async (params: ScheduleVolumeChangeParams) => {
    setSavingSchedule(true);
    try {
      const res = await volumeScheduler.scheduleVolumeChange(params);
      setFeedback({ ok: res.success, text: res.message });
      setSheetOpen(false);
      setEditingSchedule(null);
      await loadAll();
    } finally {
      setSavingSchedule(false);
    }
  };

  const handleDeleteSchedule = async (id: string) => {
    setBusyScheduleId(id);
    try {
      const res = await volumeScheduler.deleteScheduledChange(id);
      if (res.success) {
        setFeedback({ ok: true, text: 'Schedule deleted and native alarm cancelled.' });
      }
      await loadAll();
    } finally {
      setBusyScheduleId(null);
    }
  };

  const handleToggleRule = async (id: string, enabled: boolean) => {
    setBusyScheduleId(id);
    try {
      const res = await volumeScheduler.toggleScheduleEnabled(id, enabled);
      setFeedback({ ok: res.success, text: res.message });
      await loadAll();
    } finally {
      setBusyScheduleId(null);
    }
  };

  const handleRequestPermission = async (type: PermissionActionType) => {
    setBusyAction(`perm-${type}`);
    try {
      const res = await volumeScheduler.requestRequiredPermissions(type);
      setPermMessage(res.message);
      setPermissions(res.status);
    } finally {
      setBusyAction(null);
    }
  };

  const handleRefreshLogs = async () => {
    const data = await volumeScheduler.getExecutionLogs();
    setSettings((prev) =>
      prev
        ? {
            ...prev,
            recentLogs: data.logs,
            bootRestore: data.bootRestore,
            lastTestAlarmResult: data.lastTestAlarmResult,
          }
        : prev
    );
  };

  const navItems: Array<{
    id: AppTab;
    label: string;
    icon: React.ComponentType<{ className?: string }>;
  }> = [
    { id: 'dashboard', label: 'Dashboard', icon: Activity },
    { id: 'schedules', label: 'Schedules', icon: CalendarClock },
    { id: 'testing', label: 'Testing', icon: FlaskConical },
    { id: 'permissions', label: 'Permissions', icon: ShieldCheck },
  ];

  return (
    <div className="min-h-dvh bg-[#0B111A] text-slate-100 flex flex-col">
      {/* Sticky Top App Bar (compact, 3-zone on desktop, clean title + action on mobile) */}
      <header className="sticky top-0 z-30 bg-[#0B111A]/90 backdrop-blur-md border-b border-slate-800/80 pt-safe">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 h-14 flex items-center justify-between gap-8">
          <div className="flex items-center gap-2.5 min-w-0">
            <Volume2 className="w-5 h-5 text-cyan-400 shrink-0" />
            <span className="text-base sm:text-lg font-semibold tracking-tight text-slate-100 whitespace-nowrap truncate">
              Smart Volume Scheduler
            </span>
          </div>

          <nav
            className="hidden md:flex items-center gap-6 text-sm font-medium"
            aria-label="Primary navigation"
          >
            {navItems.map((item) => {
              const active = activeTab === item.id;
              return (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => setActiveTab(item.id)}
                  className={`py-1.5 border-b-2 transition-colors whitespace-nowrap shrink-0 ${
                    active
                      ? 'border-cyan-400 text-cyan-300'
                      : 'border-transparent text-slate-400 hover:text-slate-100'
                  }`}
                >
                  {item.label}
                </button>
              );
            })}
          </nav>

          <button
            type="button"
            onClick={() => {
              setEditingSchedule(null);
              setSheetOpen(true);
            }}
            className="min-h-[40px] px-3.5 py-1.5 rounded-xl bg-cyan-400 text-[#0B111A] text-xs font-semibold hover:bg-cyan-300 transition-colors flex items-center gap-1.5 whitespace-nowrap shrink-0"
          >
            <Plus className="w-4 h-4" />
            <span>Add Rule</span>
          </button>
        </div>
      </header>

      {/* Main Content */}
      <main className="flex-1 w-full max-w-6xl mx-auto px-4 sm:px-6 py-5 pb-24 md:pb-10">
        {loading || !settings || !permissions ? (
          <div className="py-16 text-center text-sm text-slate-400">
            Reading native audio and schedule state...
          </div>
        ) : (
          <div className="space-y-5">
            {!settings.native && (
              <div className="bg-[#121A26] border border-slate-800 rounded-2xl px-4 py-3 text-xs text-slate-300 flex flex-col sm:flex-row sm:items-center justify-between gap-2">
                <span>
                  <span className="font-semibold text-cyan-300">Web Preview Mode</span>
                  <span className="text-slate-500 mx-1.5" aria-hidden="true">·</span>
                  Native Android AudioManager, AlarmManager, and BootCompletedReceiver execute inside the compiled Android APK.
                </span>
                <span className="font-mono-tabular text-slate-400 whitespace-nowrap shrink-0">
                  Target SDK 36 · Android 16 Ready
                </span>
              </div>
            )}

            {activeTab === 'dashboard' && (
              <DashboardTab
                settings={settings}
                permissions={permissions}
                busyAction={busyAction}
                lastFeedback={feedback}
                onRefresh={() => loadAll(true)}
                onApplyVolume={handleApplyVolume}
                onApplyPreset={handleApplyPreset}
                onToggleScheduler={handleToggleScheduler}
                onGoToSchedules={() => setActiveTab('schedules')}
                onGoToPermissions={() => setActiveTab('permissions')}
              />
            )}

            {activeTab === 'schedules' && (
              <SchedulesTab
                schedules={settings.schedules}
                schedulerEnabled={settings.schedulerEnabled}
                busyId={busyScheduleId}
                onAdd={() => {
                  setEditingSchedule(null);
                  setSheetOpen(true);
                }}
                onEdit={(sched) => {
                  setEditingSchedule(sched);
                  setSheetOpen(true);
                }}
                onDelete={handleDeleteSchedule}
                onToggleRule={handleToggleRule}
              />
            )}

            {activeTab === 'testing' && (
              <TestingTab
                isNative={settings.native}
                permissions={permissions}
                bootRestore={settings.bootRestore}
                logs={settings.recentLogs}
                lastTestAlarmResult={settings.lastTestAlarmResult}
                onTestStream={(stream, pct) =>
                  volumeScheduler.testVolumeStream(stream, pct).then(async (r) => {
                    await loadAll();
                    return r;
                  })
                }
                onScheduleTestAlarm={(sec, stream, pct) =>
                  volumeScheduler.scheduleTestAlarm(sec, stream, pct)
                }
                onProbeAudioMode={() => volumeScheduler.getAudioModeProbe()}
                onRefreshLogs={handleRefreshLogs}
              />
            )}

            {activeTab === 'permissions' && (
              <PermissionsTab
                permissions={permissions}
                busyAction={busyAction}
                lastMessage={permMessage}
                onRefresh={() => loadAll(true)}
                onRequestPermission={handleRequestPermission}
              />
            )}
          </div>
        )}
      </main>

      {/* Mobile Thumb-Zone Bottom Tab Bar */}
      <nav
        className="md:hidden fixed bottom-0 left-0 right-0 z-40 bg-[#0B111A]/95 backdrop-blur-md border-t border-slate-800/90 pb-safe"
        aria-label="Mobile navigation"
      >
        <div className="grid grid-cols-4 h-16 items-center">
          {navItems.map((item) => {
            const Icon = item.icon;
            const active = activeTab === item.id;
            return (
              <button
                key={item.id}
                type="button"
                onClick={() => setActiveTab(item.id)}
                className={`min-h-[48px] flex flex-col items-center justify-center gap-1 transition-colors ${
                  active ? 'text-cyan-400' : 'text-slate-400 hover:text-slate-200'
                }`}
              >
                <Icon className="w-5 h-5" />
                <span className="text-[11px] font-medium tracking-tight leading-none">
                  {item.label}
                </span>
              </button>
            );
          })}
        </div>
      </nav>

      <ScheduleFormSheet
        open={sheetOpen}
        initialSchedule={editingSchedule}
        saving={savingSchedule}
        onClose={() => {
          setSheetOpen(false);
          setEditingSchedule(null);
        }}
        onSave={handleSaveSchedule}
      />
    </div>
  );
}
