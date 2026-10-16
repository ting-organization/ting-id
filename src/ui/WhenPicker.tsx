// When a payment goes: now, or at a date and time picked with the phone's
// own date and time pickers. The browser build, for development, uses the
// browser's.
import React from 'react';
import { Platform, Pressable, Text, View } from 'react-native';
import { DateTimePickerAndroid } from '@react-native-community/datetimepicker';
import Ionicons from '@expo/vector-icons/Ionicons';
import { formatClock, nowSeconds } from '@/chain/format';
import { Caption, IconName, Segmented } from './components';
import { font, radius, spacing, tabular, useTheme } from './theme';
import { LATEST, atTime, formatDay, onDay } from './when';

export type When = 'now' | 'later';

/**
 * `lock`: the sending account's lock, in seconds; with one, "now" is the
 * earliest it allows, and a time before the lock ends waits for it.
 */
export function WhenPicker({ when, at, lock, onWhen, onAt }: { when: When; at: number; lock: number; onWhen: (w: When) => void; onAt: (at: number) => void }) {
  const now = nowSeconds();
  const pick = (mode: 'date' | 'time') =>
    DateTimePickerAndroid.open({
      value: new Date(at * 1000),
      mode,
      minimumDate: new Date(now * 1000),
      maximumDate: new Date((now + LATEST) * 1000),
      onChange: (event, picked) => {
        if (event.type !== 'set' || !picked) return;
        onAt(mode === 'date' ? onDay(at, picked) : atTime(at, picked));
      },
    });
  const lockEnds = now + lock;
  return (
    <View style={{ gap: spacing.sm }}>
      <Segmented
        options={[
          { value: 'now', label: lock ? 'Earliest' : 'Now' },
          { value: 'later', label: 'Later' },
        ]}
        value={when}
        onChange={onWhen}
      />
      {when === 'later' ? (
        Platform.OS === 'web' ? (
          <BrowserPicker at={at} now={now} onAt={onAt} />
        ) : (
          <View style={{ flexDirection: 'row', gap: spacing.sm }}>
            <Choice icon="calendar-outline" label={formatDay(at)} onPress={() => pick('date')} accessibilityLabel="Change the date" />
            <Choice icon="time-outline" label={formatClock(at)} onPress={() => pick('time')} accessibilityLabel="Change the time" />
          </View>
        )
      ) : null}
      {lock && (when === 'now' || at < lockEnds) ? (
        <Caption>
          It runs when the lock ends, {formatDay(lockEnds)} at {formatClock(lockEnds)}, and can be cancelled until then.
        </Caption>
      ) : null}
    </View>
  );
}

function Choice({ icon, label, onPress, accessibilityLabel }: { icon: IconName; label: string; onPress: () => void; accessibilityLabel: string }) {
  const t = useTheme();
  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel}
      style={({ pressed }) => ({
        flex: 1,
        flexDirection: 'row',
        alignItems: 'center',
        gap: 8,
        minHeight: 46,
        paddingHorizontal: 14,
        borderRadius: radius.md,
        borderWidth: 1,
        borderColor: t.border,
        backgroundColor: t.card,
        opacity: pressed ? 0.75 : 1,
      })}>
      <Ionicons name={icon} size={17} color={t.primary} />
      <Text style={[{ color: t.text, fontSize: 14.5, ...font.bold, flexShrink: 1 }, tabular]} numberOfLines={1}>
        {label}
      </Text>
    </Pressable>
  );
}

/** `at` as the browser's local date-time value, `2026-10-17T14:00`. */
function localValue(at: number): string {
  const d = new Date(at * 1000);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}

function BrowserPicker({ at, now, onAt }: { at: number; now: number; onAt: (at: number) => void }) {
  const t = useTheme();
  return React.createElement('input', {
    type: 'datetime-local',
    'aria-label': 'Date and time',
    value: localValue(at),
    min: localValue(now),
    max: localValue(now + LATEST),
    onChange: (e: { target: { value: string } }) => {
      const ms = new Date(e.target.value).getTime();
      if (Number.isFinite(ms)) onAt(Math.floor(ms / 1000));
    },
    style: {
      font: 'inherit',
      fontSize: 15,
      padding: '11px 14px',
      borderRadius: radius.md,
      border: `1px solid ${t.border}`,
      background: t.card,
      color: t.text,
    },
  });
}
