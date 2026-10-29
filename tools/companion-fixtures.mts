/**
 * Companion payload fixtures for the Mac's conversation runner, built from server-platform's own
 * samples and checked against its zod schemas, so a fixture exists only if the server would accept
 * it. `tools/sync-server-fixtures.py` runs this with the server's `tsx` (from its `event-contracts`
 * folder) and embeds the JSON it prints in `CompanionContractFixtures.kt`; the Kotlin test reads each
 * one with the Mac's types and writes it back unchanged.
 *
 *     node --import tsx <gamero>/tools/companion-fixtures.mts <server-platform>
 */
import { createHash } from 'node:crypto';

const server = process.argv[2];
if (!server) throw new Error('Name the server-platform checkout');
const source = `${server}/event-contracts/src/hovel`;
const c = await import(`${source}/companions/index.ts`);
const s = await import(`${source}/companions/samples.test-support.ts`);
const { hovelHostFeature } = await import(`${source}/hovel.schemas.ts`);
const { allowanceSnapshot } = await import(`${source}/allowance/index.ts`);

type Schema = { parse: (value: unknown) => unknown };
const fixtures: Record<string, { schema: string; value: unknown }> = {};

/** [value] as JSON with every object's keys sorted, so two values compare by content alone. */
function canonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value !== null && typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>).filter(([, item]) => item !== undefined).sort(([a], [b]) => a.localeCompare(b));
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

const { ids, at, later } = s;
const claimed = { claimId: ids.claim, claimedMessageId: ids.message };
const threadClaim = {
  ...s.claim,
  message: { ...s.claimed, threadId: ids.thread },
  thread: { ...s.task, state: 'completed' },
  recalledMemory: 'Prefers short answers.'
};
const run = {
  configurationRevision: 4, currentThreadId: ids.thread, dispatchesUsed: 1, id: ids.claim, returnsUsed: 0, revision: 2,
  skipped: [], state: 'running', workflowId: null
};

add('claim.claimed', 'claimResponse', c.claimResponse, { claim: s.claim, status: 'claimed' });
add('claim.thread', 'claimResponse', c.claimResponse, { claim: threadClaim, status: 'claimed' });
add('claim.empty', 'claimResponse', c.claimResponse, { status: 'empty' });
add('claim.macUpdateRequired', 'claimResponse', c.claimResponse, { minimumContract: 2, status: 'mac-update-required' });
add('claimRequest', 'claimRequest', c.claimRequest, { claimId: ids.claim, conversationId: ids.conversation });

add('checkpoint.threadUpserted', 'messageCheckpointRequest', c.messageCheckpointRequest,
  { ...claimed, checkpoint: { kind: 'thread.upserted', thread: s.task } });
add('checkpoint.coordinatorReply', 'messageCheckpointRequest', c.messageCheckpointRequest,
  { ...claimed, checkpoint: { kind: 'message.appended', message: { author: 'coordinator', id: ids.reply, text: 'I will ask the Implementer.', threadId: null } } });
add('checkpoint.specialistReply', 'messageCheckpointRequest', c.messageCheckpointRequest,
  { ...claimed, checkpoint: { kind: 'message.appended', message: { author: 'specialist', id: ids.reply, text: 'Sign-in added.', threadId: ids.thread } } });
add('checkpoint.forwardedNote', 'messageCheckpointRequest', c.messageCheckpointRequest, {
  ...claimed,
  checkpoint: { kind: 'message.appended', message: { author: 'coordinator', id: ids.reply, note: { kind: 'forwarded', threadId: ids.thread }, text: 'Forwarded to Implementer.', threadId: ids.thread } }
});
add('checkpoint.startedNote', 'messageCheckpointRequest', c.messageCheckpointRequest, {
  ...claimed,
  checkpoint: { kind: 'message.appended', message: { author: 'coordinator', id: ids.reply, note: { kind: 'started', threadId: ids.thread2 }, text: 'Started a new task.', threadId: ids.thread } }
});
add('checkpoint.systemNotice', 'messageCheckpointRequest', c.messageCheckpointRequest,
  { ...claimed, checkpoint: { kind: 'message.appended', message: { author: 'system', id: ids.reply, text: 'Work paused.', threadId: null } } });
add('checkpoint.textArtifact', 'messageCheckpointRequest', c.messageCheckpointRequest,
  { ...claimed, checkpoint: { artifact: s.summary, kind: 'artifact.stored', text: 'Sign-in added.' } });
add('checkpoint.binaryArtifact', 'messageCheckpointRequest', c.messageCheckpointRequest, {
  ...claimed,
  checkpoint: { artifact: { ...s.summary, mediaType: 'application/octet-stream', preview: 'Attached file', title: 'photo.png' }, kind: 'artifact.stored' }
});
add('checkpoint.runChanged', 'messageCheckpointRequest', c.messageCheckpointRequest, { ...claimed, checkpoint: { kind: 'run.changed', run } });
add('checkpoint.approvalOpened', 'messageCheckpointRequest', c.messageCheckpointRequest,
  { ...claimed, checkpoint: { approval: s.openApproval, kind: 'approval.opened' } });
add('checkpoint.approvalMacOnly', 'messageCheckpointRequest', c.messageCheckpointRequest, {
  ...claimed,
  checkpoint: {
    approval: { ...s.openApproval, kind: 'stage', options: ['continue', 'request-changes', 'not-now', 'stop'],
      presentation: { reach: 'mac-only', summary: 'Ember wants to run a command. Answer it on the Mac.' }, revision: 0 },
    kind: 'approval.opened'
  }
});
add('checkpoint.approvalWithdrawn', 'messageCheckpointRequest', c.messageCheckpointRequest,
  { ...claimed, checkpoint: { approvalId: ids.approval, kind: 'approval.closed', outcome: { kind: 'withdrawn' } } });
add('checkpoint.approvalAnsweredHere', 'messageCheckpointRequest', c.messageCheckpointRequest,
  { ...claimed, checkpoint: { approvalId: ids.approval, kind: 'approval.closed', outcome: { kind: 'answered', option: 'allow-once' } } });

add('delta', 'replyDeltaRequest', c.replyDeltaRequest, { ...claimed, delta: { messageId: ids.reply, seq: 0, text: 'Hel', threadId: ids.thread } });
add('progress.running', 'messageProgress', c.messageProgress, { ...claimed, expectedRevision: 2, state: 'running' });
add('progress.reconciliation', 'messageProgress', c.messageProgress, { ...claimed, expectedRevision: 3, state: 'reconciliation-required' });

add('approval.answeredByPhone', 'agentApproval', c.agentApproval,
  { ...s.openApproval, answer: { by: 'phone', deviceId: ids.computer, option: 'deny' }, revision: 2, state: 'answered' });
add('approvalAnswer', 'approvalAnswer', c.approvalAnswer, { expectedRevision: 0, option: 'allow-once' });

for (const event of [
  { conversationId: ids.conversation, type: 'work.available' },
  { messageId: ids.message, type: 'message.cancelled' },
  { conversationId: ids.conversation, type: 'stop.requested' },
  { approvalId: ids.approval, type: 'approval.answered' },
  { projectId: ids.project, type: 'project.changed' }
]) add(`hostEvent.${event.type}`, 'hostStreamEvent', c.hostStreamEvent, event);

add('projectFoldersSave', 'projectFoldersSave', c.projectFoldersSave,
  { expectedRevision: 3, folders: [s.working, { ...s.contextFolder(1), readableBy: [] }] });
add('messageSend.heard', 'messageSend', c.messageSend, { id: ids.message, inputs: [], text: 'What is next?', threadId: null });

// The heartbeat: `computerHeartbeat` beside the memory features the server keeps reading.
const usage = {
  account: { accountRef: nameUuid(`hovel-allowance:${ids.computer}:claude-code`), providerId: 'anthropic', subscriptionRef: null },
  buckets: [
    { bucketId: 'session', label: 'Session', measurement: { kind: 'percent', unit: 'percent', used: 12.5 }, modelIds: [], observedAt: 1000,
      scope: 'account', window: { durationMinutes: 300, id: 'session', resetsAt: 1_800_000_000_000, startsAt: null } },
    { bucketId: 'weekly', label: 'Weekly', measurement: { kind: 'percent', unit: 'percent', used: 40 }, modelIds: [], observedAt: 1000,
      scope: 'account', window: { durationMinutes: 10080, id: 'weekly', resetsAt: null, startsAt: null } }
  ],
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
      { canWork: false, models: [{ displayName: 'GPT', isDefault: true, modelId: 'gpt-test', effortLevels: ['low', 'high'], defaultEffort: 'low' }],
        readiness: 'ready', runtimeId: 'codex' },
      { canWork: true, models: [], readiness: 'ready', runtimeId: 'claude-code' },
      { canWork: false, models: [], readiness: 'unavailable', runtimeId: 'grok-build' }
    ]
  }
};
c.computerHeartbeat.parse(heartbeat);
for (const feature of ['memory-context', 'memory-extraction']) hovelHostFeature.parse(feature);
fixtures.heartbeat = { schema: 'computerHeartbeat+features', value: JSON.parse(canonical({ ...heartbeat, features: ['memory-context', 'memory-extraction'] })) };
fixtures.usage = { schema: 'allowanceSnapshot', value: JSON.parse(canonical({ computerId: ids.computer, snapshot: usage })) };

void later;
process.stdout.write(JSON.stringify(fixtures));
