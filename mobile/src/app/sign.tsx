// A site's signing request (SIGNING.md): fetched from the address in its code,
// shown with the facts the app vouches for apart from what the site wrote, and
// answered with the user's signature or a refusal.
import React, { useEffect, useState } from 'react';
import { Linking, Platform, Pressable, Text, View } from 'react-native';
import { Stack, useLocalSearchParams, useRouter } from 'expo-router';
import Ionicons from '@expo/vector-icons/Ionicons';
import { hex } from '@/chain/bytes';
import { nowSeconds, relativeTime } from '@/chain/format';
import { SignRequest, Span, bodyBlocks, matchCode, parseSignCode } from '@/chain/offchain';
import { declineRequest, fetchAttachment, fetchSignRequest, possibleSigners, signAndAnswer, signerProblem } from '@/state/signing';
import { useStore } from '@/state/store';
import { useWorkspace } from '@/state/workspace';
import { Avatar, Banner, Body, Button, Caption, Card, Field, IconCircle, Loading, Pill, Row, Screen, Sub } from '@/ui/components';
import { describeSignRequest, sentence, siteName } from '@/ui/describe';
import { openFile } from '@/ui/openFile';
import { font, radius, spacing, tabular, type, useTheme } from '@/ui/theme';

type Step =
  | { kind: 'enter' }
  | { kind: 'loading'; url: string }
  | { kind: 'failed'; message: string }
  | { kind: 'review'; request: SignRequest }
  | { kind: 'sending'; request: SignRequest }
  | { kind: 'done'; request: SignRequest; outcome: 'signed' | 'declined'; message: string };

/** Each code opens its own review: a new link while one is open starts afresh. */
export default function SignScreen() {
  const params = useLocalSearchParams<{ code?: string }>();
  return <SignFlow key={params.code ?? ''} linked={params.code ?? ''} />;
}

function SignFlow({ linked }: { linked: string }) {
  const t = useTheme();
  const router = useRouter();
  // A link may have opened the app on this screen: then there is nothing to go back to.
  const leave = () => (router.canGoBack() ? router.back() : router.replace('/'));
  const w = useWorkspace();
  const users = useStore((s) => s.users);
  const setActiveUser = useStore((s) => s.setActiveUser);
  const addSignature = useStore((s) => s.addSignature);
  const [code, setCode] = useState('');
  const [step, setStep] = useState<Step>(() => {
    const url = parseSignCode(linked);
    return url ? { kind: 'loading', url } : { kind: 'enter' };
  });
  const [error, setError] = useState<string | null>(null);
  const chainHex = w.info ? hex(w.info.status.chainId) : null;

  useEffect(() => {
    if (step.kind !== 'loading') return;
    let alive = true;
    fetchSignRequest(step.url)
      .then((request) => alive && setStep({ kind: 'review', request }))
      .catch((e) => alive && setStep({ kind: 'failed', message: (e as Error).message }));
    return () => {
      alive = false;
    };
  }, [step]);

  const open = () => {
    const url = parseSignCode(code);
    if (!url) {
      setError('That is not a signing code from a secure site.');
      return;
    }
    setError(null);
    setStep({ kind: 'loading', url });
  };

  const note = (request: SignRequest, outcome: 'signed' | 'declined' | 'failed', message: string | null) =>
    w.user &&
    addSignature({ at: nowSeconds(), userId: w.user.id, origin: request.origin, kind: request.request.request.kind, title: request.request.title, outcome, message });

  const sign = async (request: SignRequest) => {
    if (!w.user || !w.info) return;
    setStep({ kind: 'sending', request });
    setError(null);
    try {
      const message = await signAndAnswer(request, w.user, w.info.canonical, w.keys, w.identity);
      note(request, 'signed', message);
      setStep({ kind: 'done', request, outcome: 'signed', message });
      // On a phone, a site that asked from its browser page gets the user back.
      if (request.returnTo && Platform.OS !== 'web') Linking.openURL(request.returnTo).catch(() => {});
    } catch (e) {
      const message = (e as Error).message;
      note(request, 'failed', message);
      setError(message);
      setStep({ kind: 'review', request });
    }
  };

  const decline = async (request: SignRequest) => {
    setStep({ kind: 'sending', request });
    await declineRequest(request);
    note(request, 'declined', null);
    setStep({ kind: 'done', request, outcome: 'declined', message: 'The site was told you declined.' });
  };

  return (
    <Screen belowHeader>
      <Stack.Screen options={{ title: 'Signing request' }} />
      {step.kind === 'enter' ? (
        <Card>
          <Body>Paste the code a site shows for Ting ID, or scan it from the wallet.</Body>
          <Field label="Code" value={code} onChangeText={setCode} placeholder="ting:sign?url=…" autoCapitalize="none" autoCorrect={false} />
          {error ? <Banner tone="danger">{sentence(error)}</Banner> : null}
          <Button title="Open" icon="arrow-up" onPress={open} disabled={!code.trim()} />
        </Card>
      ) : null}
      {step.kind === 'loading' ? (
        <Card>
          <Loading />
          <Caption style={{ textAlign: 'center' }}>Asking the site for the request…</Caption>
        </Card>
      ) : null}
      {step.kind === 'failed' ? (
        <Card style={{ alignItems: 'center', paddingVertical: spacing.xl, gap: spacing.sm }}>
          <IconCircle name="alert-circle-outline" color={t.danger} bg={t.dangerSoft} size={48} />
          <Sub>This request can’t be signed</Sub>
          <Caption style={{ textAlign: 'center' }}>{sentence(step.message)}</Caption>
          <Button title="Close" kind="secondary" onPress={leave} style={{ alignSelf: 'stretch', marginTop: spacing.sm }} />
        </Card>
      ) : null}
      {step.kind === 'review' || step.kind === 'sending' ? (
        <Review
          key={hex(step.request.nonce)}
          request={step.request}
          busy={step.kind === 'sending'}
          error={error}
          problem={signerProblem(step.request, w.user, chainHex)}
          signers={possibleSigners(step.request, users, chainHex)}
          onSigner={setActiveUser}
          onSign={() => sign(step.request)}
          onDecline={() => decline(step.request)}
        />
      ) : null}
      {step.kind === 'done' ? (
        <Card>
          <Row>
            <IconCircle
              name={step.outcome === 'signed' ? 'checkmark-circle-outline' : 'close-circle-outline'}
              color={step.outcome === 'signed' ? t.success : t.muted}
              bg={step.outcome === 'signed' ? t.successSoft : t.cardAlt}
              size={44}
            />
            <View style={{ flex: 1, gap: 2 }}>
              <Sub>{step.outcome === 'signed' ? `Signed for ${siteName(step.request.origin)}` : 'Declined'}</Sub>
              <Caption>
                {step.outcome === 'signed' ? `${siteName(step.request.origin)} says: ` : ''}
                {step.message}
              </Caption>
            </View>
          </Row>
          {step.request.returnTo ? (
            <Button title={`Back to ${siteName(step.request.origin)}`} icon="arrow-up" onPress={() => Linking.openURL(step.request.returnTo!).catch(() => {})} />
          ) : null}
          <Button title="Done" kind={step.request.returnTo ? 'ghost' : 'primary'} onPress={leave} />
        </Card>
      ) : null}
      <View style={{ height: spacing.lg }} />
    </Screen>
  );
}

function Spans({ spans }: { spans: Span[] }) {
  const t = useTheme();
  return (
    <Text style={[type.body, { color: t.text }]}>
      {spans.map((s, k) => (
        <Text key={k} style={s.bold ? font.bold : s.italic ? { fontStyle: 'italic' } : undefined}>
          {s.text}
        </Text>
      ))}
    </Text>
  );
}

function Review({
  request,
  busy,
  error,
  problem,
  signers,
  onSigner,
  onSign,
  onDecline,
}: {
  request: SignRequest;
  busy: boolean;
  error: string | null;
  problem: string | null;
  signers: { id: string; name: string; color: string }[];
  onSigner: (id: string) => void;
  onSign: () => void;
  onDecline: () => void;
}) {
  const t = useTheme();
  const w = useWorkspace();
  const doc = request.request;
  const d = describeSignRequest(doc.request, w.user?.name ?? 'you');
  const site = siteName(request.origin);
  const secure = request.origin.startsWith('https://');
  const [, tick] = useState(0);
  useEffect(() => {
    const timer = setInterval(() => tick((n) => n + 1), 15_000);
    return () => clearInterval(timer);
  }, []);
  const expired = Number(request.expiresAt) <= nowSeconds();
  // A statement's file is fetched, checked against its hash, and opened before it can be signed.
  const attachment = doc.request.kind === 'statement' ? doc.request.attachment : null;
  const [file, setFile] = useState<{ state: 'idle' | 'opening' | 'opened' | 'failed'; message?: string }>({ state: 'idle' });
  // A request that just appeared, perhaps under the pointer, cannot be signed by the same click.
  const [armed, setArmed] = useState(false);
  useEffect(() => {
    const t = setTimeout(() => setArmed(true), 1000);
    return () => clearTimeout(t);
  }, []);
  const open = async () => {
    if (!attachment) return;
    setFile({ state: 'opening' });
    try {
      const bytes = await fetchAttachment(request);
      await openFile(bytes, attachment.name, attachment.mediaType);
      setFile({ state: 'opened' });
    } catch (e) {
      setFile({ state: 'failed', message: (e as Error).message });
    }
  };
  const unopened = !!attachment && file.state !== 'opened';
  return (
    <>
      {/* Who asks: the site, as the app knows it. */}
      <Row gap={12}>
        <View style={{ width: 40, height: 40, borderRadius: radius.md, backgroundColor: t.card, borderWidth: 1, borderColor: t.border, alignItems: 'center', justifyContent: 'center' }}>
          <Ionicons name="globe-outline" size={20} color={t.muted} />
        </View>
        <View style={{ flex: 1, gap: 2 }}>
          <Text style={{ color: t.text, fontSize: 15, ...font.bold }} numberOfLines={2}>
            {site}
          </Text>
          {secure ? (
            <Row gap={4}>
              <Ionicons name="lock-closed" size={12} color={t.success} />
              <Caption>Secure connection</Caption>
            </Row>
          ) : null}
        </View>
        {secure ? null : (
          <View style={{ alignSelf: 'center' }}>
            <Pill label="In development" tone="warning" small />
          </View>
        )}
      </Row>

      {/* What signing does, in the app's words, and who signs. */}
      <View style={{ gap: 4, marginTop: spacing.xs }}>
        <Text style={[type.heading, { color: t.text }]}>{d.headline}</Text>
        {d.detail ? <Caption>{d.detail}</Caption> : null}
      </View>
      {doc.request.kind === 'grant' && doc.request.scopes.length > 0 ? (
        <Row gap={6} style={{ flexWrap: 'wrap' }}>
          {doc.request.scopes.map((s) => (
            <Text key={s} style={{ fontFamily: type.mono.fontFamily, fontSize: 12.5, color: t.text, backgroundColor: t.cardAlt, borderWidth: 1, borderColor: t.border, borderRadius: 6, paddingHorizontal: 8, paddingVertical: 2 }}>
              {s}
            </Text>
          ))}
        </Row>
      ) : null}
      {signers.length > 1 ? (
        <View style={{ gap: 6 }}>
          <Caption>Sign as</Caption>
          <Row style={{ flexWrap: 'wrap' }}>
            {signers.map((u) => (
              <Pressable
                key={u.id}
                disabled={busy}
                onPress={() => onSigner(u.id)}
                accessibilityRole="radio"
                accessibilityState={{ selected: u.id === w.user?.id }}
                style={{
                  flexDirection: 'row',
                  alignItems: 'center',
                  gap: 6,
                  paddingVertical: 4,
                  paddingLeft: 4,
                  paddingRight: 12,
                  borderRadius: 999,
                  borderWidth: 1,
                  borderColor: u.id === w.user?.id ? t.ink : t.border,
                  backgroundColor: t.card,
                }}>
                <Avatar name={u.name} color={u.color} size={24} />
                <Text style={{ color: t.text, fontSize: 13.5, ...font.semibold }}>{u.name}</Text>
              </Pressable>
            ))}
          </Row>
        </View>
      ) : null}
      {d.warning ? <Banner tone="warning">{d.warning}</Banner> : null}

      {/* The file the statement covers: only the exact file it names opens. */}
      {attachment ? (
        <View style={{ gap: 8 }}>
          <Row gap={12} style={{ backgroundColor: t.card, borderWidth: 1, borderColor: file.state === 'opened' ? t.success : t.border, borderRadius: radius.lg, padding: spacing.md }}>
            <IconCircle name="document-text-outline" color={t.muted} bg={t.cardAlt} size={40} />
            <View style={{ flex: 1, gap: 1 }}>
              <Text style={{ color: t.text, fontSize: 14.5, ...font.bold }} numberOfLines={2}>
                {attachment.name}
              </Text>
              <Caption>{file.state === 'opened' ? 'Checked and opened' : attachment.mediaType}</Caption>
            </View>
            <Button
              title="Open"
              kind="secondary"
              small
              icon={file.state === 'opened' ? 'checkmark' : 'open-outline'}
              loading={file.state === 'opening'}
              onPress={open}
            />
          </Row>
          {file.state === 'failed' ? <Banner tone="danger">{sentence(file.message ?? '')}</Banner> : null}
        </View>
      ) : null}

      {/* The number the site shows beside its code: the same one means the same request. */}
      {doc.request.kind === 'signIn' ? (
        <Row gap={16} style={{ backgroundColor: t.primarySoft, borderRadius: radius.lg, paddingVertical: 14, paddingHorizontal: spacing.lg }}>
          <Text style={[{ color: t.primary, fontSize: 40, letterSpacing: 1, lineHeight: 46, ...font.extrabold }, tabular]}>{matchCode(request.nonce)}</Text>
          <Text style={{ color: t.primary, fontSize: 14, lineHeight: 20, flex: 1, ...font.semibold }}>The site shows the same number. If it shows another, decline.</Text>
        </Row>
      ) : null}

      {/* What the site wrote, shown as written and nothing more. */}
      <View style={{ gap: 8, padding: spacing.lg, borderRadius: radius.lg, backgroundColor: t.cardAlt }}>
        <Caption style={{ color: t.faint }}>Message from the site</Caption>
        <Sub>{doc.title}</Sub>
        {bodyBlocks(doc.body).map((b, i) =>
          b.type === 'paragraph' ? (
            <View key={i}>
              {b.lines.map((spans, j) => (
                <Spans key={j} spans={spans} />
              ))}
            </View>
          ) : (
            <View key={i} style={{ gap: 4 }}>
              {b.items.map((item, j) => (
                <Row key={j} gap={8} style={{ alignItems: 'flex-start' }}>
                  <Caption style={{ minWidth: 18, textAlign: 'right' }}>{item.marker}</Caption>
                  <View style={{ flex: 1 }}>
                    <Spans spans={item.spans} />
                  </View>
                </Row>
              ))}
            </View>
          ),
        )}
        {doc.fields.length > 0 ? (
          <View style={{ marginTop: 4, borderTopWidth: 1, borderTopColor: t.border }}>
            {doc.fields.map((f, i) => (
              <Row key={i} style={{ justifyContent: 'space-between', alignItems: 'flex-start', paddingVertical: 8, borderTopWidth: i === 0 ? 0 : 1, borderTopColor: t.border }}>
                <Caption style={{ flexShrink: 0, maxWidth: '45%', marginRight: spacing.md }}>{f.label}</Caption>
                <Text style={{ color: t.text, fontSize: 14, flexShrink: 1, textAlign: 'right', ...font.semibold }}>{f.value}</Text>
              </Row>
            ))}
          </View>
        ) : null}
      </View>

      {problem ? <Banner tone="warning">This device cannot sign it: {problem}.</Banner> : null}
      {error ? <Banner tone="danger">{sentence(error)}</Banner> : null}
      <Row gap={6} style={{ justifyContent: 'center', marginTop: spacing.xs }}>
        <Ionicons name="time-outline" size={14} color={expired ? t.danger : t.faint} />
        <Caption style={{ color: expired ? t.danger : t.muted }}>
          {expired ? 'Expired. Start again on the site.' : unopened ? 'Open the file to sign' : `Expires ${relativeTime(request.expiresAt)}`}
        </Caption>
      </Row>
      <Button title={d.action} icon="finger-print-outline" large onPress={onSign} loading={busy} disabled={!!problem || expired || unopened || !armed || !w.info} />
      <Button title="Decline" kind="ghost" onPress={onDecline} disabled={busy} />
    </>
  );
}

