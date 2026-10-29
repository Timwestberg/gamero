package org.formulagen.wrapper.bridge

/** Contract dispatch without native provider effects. */
class FakeBrainCapability : BrainCapability {
    private val empty = BrainSnapshot(0, null, emptyList(), null, emptyList(), emptyList(), null, ChatVoice(), "0")
    override suspend fun read(args: BrainRead): BrainReadResult = BrainReadResult.Absent
    override suspend fun save(args: BrainSave): BrainSaveResult = BrainSaveResult.Saved(BrainDocument(1, args.configuration), emptyList())
    override suspend fun open(args: BrainOpen) = empty
    override suspend fun close(lease: BrainLease) {}
    override suspend fun send(args: BrainSend) = empty
    override suspend fun snapshot(args: BrainSnapshotRequest) = empty
    override suspend fun stop(lease: BrainLease) = empty
    override suspend fun stopSpeaking(lease: BrainLease) = empty
    override suspend fun retry(args: BrainRetry) = empty
    override suspend fun resume(args: BrainResume) = empty
    override suspend fun showApproval(args: BrainShowApproval) {}
    override suspend fun addInput(args: BrainAddInput) =
        BrainArtifact("10000000-0000-4000-8000-000000000001", args.title.trimEcma(), "a".repeat(64), BrainSource(BrainSourceKind.USER, args.messageId), "text/plain", "")
    override suspend fun chooseInput(args: BrainChooseInput): BrainArtifact? = null
    override suspend fun readOutput(args: BrainReadOutput) = BrainOutputPage("", null)
}
