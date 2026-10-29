/**
 * Companion payload fixtures for the Mac's conversation runner, built from server-platform's own
 * samples and checked against its zod schemas, so a fixture exists only if the server would accept
 * it. `tools/sync-server-fixtures.py` runs this with the server's `tsx` (from its `event-contracts`
 * folder) and embeds the JSON it prints in `CompanionContractFixtures.kt`; the Kotlin test reads each
 * one with the Mac's types and writes it back unchanged.
 *
 *     node --import tsx <gamero>/tools/companion-fixtures.mts <server-platform>
 *
 * One coupling to keep in step: the heartbeat's usage snapshot is the one `HostDirectory.allowanceSnapshot`
 * builds for a fixed reading (Claude Code at 12.5% of its session and 40% of its week). Here it is only
 * checked against the schema; `CompanionContractFixtureTest` checks the Mac builds exactly it.
 */
import { createHash } from 'node:crypto';

const server = process.argv[2];
if (!server) throw new Error('Name the server-platform checkout');
const source = `${server}/event-contracts/src/hovel`;
const c = await import(`${source}/companions/index.ts`);
const s = await import(`${source}/companions/samples.test-support.ts`);
const { hovelHostFeature, hovelProviderState } = await import(`${source}/hovel.schemas.ts`);
const { allowanceSnapshot } = await import(`${source}/allowance/index.ts`);
/** hovel-service's own heartbeat body, which the Mac's heartbeat must also satisfy. */
const service = await import(`${server}/apps/hovel-service/src/hovel.schemas.ts`);

type Schema = { parse: (value: unknown) => unknown };
const fixtures: Record<string, { schema: string; value: unknown }> = {};

/** [value] as JSON with every object's keys sorted, so two values compare by content alone. */
function canonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value !== null && typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>)
      .filter(([, item]) => item !== undefined)
      .sort(([a], [b]) => a.localeCompare(b));
    return `{${entries.map(([key, item]) => `${JSON.stringify(key)}:${canonical(item)}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

/** Keep [value] as fixture [name] once [schema] (named [schemaName]) accepts it unchanged. */
function add(name: string, schemaName: string, schema: Schema, value: unknown) {
  if (canonical(schema.parse(value)) !== canonical(value)) throw new Error(`${name}: the schema changed the value`);
  fixtures[name] = { schema: schemaName, value: JSON.parse(canonical(value)) };
}

/** Java's `UUID.nameUUIDFromBytes`: MD5 with the version 3 and IETF variant bits. */
function nameUuid(text: string): string {
  const bytes = createHash('md5').update(text, 'utf8').digest();
  bytes[6] = (bytes[6] & 0x0f) | 0x30;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString('hex');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

const { ids, at } = s;
const claimed = { claimId: ids.claim, claimedMessageId: ids.message };
const threadClaim = {
  ...s.claim,
  message: { ...s.claimed, threadId: ids.thread },
  thread: { ...s.task, state: 'completed' },
  recalledMemory: 'Prefers short answers.'
};
const run = {
  configurationRevision: 4,
  currentThreadId: ids.thread,
  dispatchesUsed: 1,
  id: ids.claim,
  returnsUsed: 0,
  revision: 2,
  skipped: [],
  state: 'running',
  workflowId: null
};

/** A checkpoint of the sample claim. */
function checkpoint(name: string, value: Record<string, unknown>) {
  add(`checkpoint.${name}`, 'messageCheckpointRequest', c.messageCheckpointRequest, { ...claimed, checkpoint: value });
}

/** A message the Mac appends. */
function appended(author: string, text: string, threadId: string | null, note?: { kind: string; threadId: string }) {
  return { kind: 'message.appended', message: { author, id: ids.reply, ...(note ? { note } : {}), text, threadId } };
}

add('claim.claimed', 'claimResponse', c.claimResponse, { claim: s.claim, status: 'claimed' });
add('claim.thread', 'claimResponse', c.claimResponse, { claim: threadClaim, status: 'claimed' });
add('claim.empty', 'claimResponse', c.claimResponse, { status: 'empty' });
add('claim.macUpdateRequired', 'claimResponse', c.claimResponse, { minimumContract: 2, status: 'mac-update-required' });
add('claimRequest', 'claimRequest', c.claimRequest, { claimId: ids.claim, conversationId: ids.conversation });

checkpoint('threadUpserted', { kind: 'thread.upserted', thread: s.task });
checkpoint('coordinatorReply', appended('coordinator', 'I will ask the Implementer.', null));
checkpoint('specialistReply', appended('specialist', 'Sign-in added.', ids.thread));
const forwarded = { kind: 'forwarded', threadId: ids.thread };
checkpoint('forwardedNote', appended('coordinator', 'Forwarded to Implementer.', ids.thread, forwarded));
checkpoint('startedNote', appended('coordinator', 'Started a new task.', ids.thread, { kind: 'started', threadId: ids.thread2 }));
checkpoint('systemNotice', appended('system', 'Work paused.', null));
checkpoint('textArtifact', { artifact: s.summary, kind: 'artifact.stored', text: 'Sign-in added.' });
checkpoint('binaryArtifact', {
  artifact: { ...s.summary, mediaType: 'application/octet-stream', preview: 'Attached file', title: 'photo.png' },
  kind: 'artifact.stored'
});
checkpoint('runChanged', { kind: 'run.changed', run });
checkpoint('approvalOpened', { approval: s.openApproval, kind: 'approval.opened' });
checkpoint('approvalMacOnly', {
  approval: {
    ...s.openApproval,
    kind: 'stage',
    options: ['continue', 'request-changes', 'not-now', 'stop'],
    presentation: { reach: 'mac-only', summary: 'Ember wants to run a command. Answer it on the Mac.' },
    revision: 0
  },
  kind: 'approval.opened'
});
checkpoint('approvalWithdrawn', { approvalId: ids.approval, kind: 'approval.closed', outcome: { kind: 'withdrawn' } });
checkpoint('approvalAnsweredHere', {
  approvalId: ids.approval,
  kind: 'approval.closed',
  outcome: { kind: 'answered', option: 'allow-once' }
});

add('delta', 'replyDeltaRequest', c.replyDeltaRequest, {
  ...claimed,
  delta: { messageId: ids.reply, seq: 0, text: 'Hel', threadId: ids.thread }
});
add('progress.running', 'messageProgress', c.messageProgress, { ...claimed, expectedRevision: 2, state: 'running' });
add('progress.reconciliation', 'messageProgress', c.messageProgress, {
  ...claimed,
  expectedRevision: 3,
  state: 'reconciliation-required'
});

add('approval.answeredByPhone', 'agentApproval', c.agentApproval, {
  ...s.openApproval,
  answer: { by: 'phone', deviceId: ids.computer, option: 'deny' },
  revision: 2,
  state: 'answered'
});
add('approval.expired', 'agentApproval', c.agentApproval, { ...s.openApproval, revision: 2, state: 'expired' });
add('project', 'companionProject', c.companionProject, { ...s.notes, folders: [s.working, s.contextFolder(1)] });
add('artifactText.page', 'artifactTextPage', c.artifactTextPage, { nextCursor: 3, text: 'Sig' });
add('artifactText.last', 'artifactTextPage', c.artifactTextPage, { nextCursor: null, text: 'n-in added.' });

for (const event of [
  { conversationId: ids.conversation, type: 'work.available' },
  { messageId: ids.message, type: 'message.cancelled' },
  { conversationId: ids.conversation, type: 'stop.requested' },
  { approvalId: ids.approval, type: 'approval.answered' },
  { projectId: ids.project, type: 'project.changed' }
]) {
  add(`hostEvent.${event.type}`, 'hostStreamEvent', c.hostStreamEvent, event);
}

add('projectFoldersSave', 'projectFoldersSave', c.projectFoldersSave, {
  expectedRevision: 3,
  folders: [s.working, { ...s.contextFolder(1), readableBy: [] }]
});

// The heartbeat: hovel-service's body (`providerState`, `features`) plus `computerHeartbeat`.
/** One usage bucket: a percent of a window, for the whole account. */
function bucket(id: string, label: string, used: number, minutes: number, resetsAt: number | null) {
  return {
    bucketId: id,
    label,
    measurement: { kind: 'percent', unit: 'percent', used },
    modelIds: [],
    observedAt: 1000,
    scope: 'account',
    window: { durationMinutes: minutes, id, resetsAt, startsAt: null }
  };
}
const usage = {
  account: {
    accountRef: nameUuid(`hovel-allowance:${ids.computer}:claude-code`),
    providerId: 'anthropic',
    subscriptionRef: null
  },
  buckets: [bucket('session', 'Session', 12.5, 300, 1_800_000_000_000), bucket('weekly', 'Weekly', 40, 10080, null)],
  observedAt: 1000,
  source: { capability: 'poll', interface: 'hovel-native-usage', runtimeId: 'claude-code' },
  staleAfterMs: 300_000,
  state: 'available',
  version: 1
};
allowanceSnapshot.parse(usage);
const heartbeat = {
  allowances: [{ runtimeId: 'claude-code', snapshot: usage }],
  directory: {
    observedAt: at,
    runtimes: [
      {
        canWork: false,
        models: [{ defaultEffort: 'low', displayName: 'GPT', effortLevels: ['low', 'high'], isDefault: true, modelId: 'gpt-test' }],
        readiness: 'ready',
        runtimeId: 'codex'
      },
      { canWork: true, models: [], readiness: 'ready', runtimeId: 'claude-code' },
      { canWork: false, models: [], readiness: 'unavailable', runtimeId: 'grok-build' }
    ]
  }
};
c.computerHeartbeat.parse(heartbeat);
const features = ['provider-input', 'memory-context', 'memory-extraction'];
for (const feature of features) hovelHostFeature.parse(feature);
const beat = { ...heartbeat, features, providerState: hovelProviderState.parse('ready') };
if ('directory' in service.heartbeatInput.shape) {
  service.heartbeatInput.parse(beat);
} else {
  process.stderr.write(
    'This hovel-service heartbeat predates computerHeartbeat; the heartbeat fixture is checked against the contracts only.\n'
  );
}
fixtures.heartbeat = { schema: 'heartbeatInput', value: JSON.parse(canonical(beat)) };
fixtures.usage = {
  schema: 'allowanceSnapshot',
  value: JSON.parse(canonical({ computerId: ids.computer, snapshot: usage }))
};

process.stdout.write(JSON.stringify(fixtures));
