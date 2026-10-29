# Local Grok work-auth decision

Applied with the user's explicit approval on 2026-10-28. The following section was appended to `~/.grok/config.toml` while preserving every existing setting:

```toml
[auth]
preferred_method = "oidc"
```

This makes Grok use account/browser-login credentials and reject API-key fallback. It does not change the credential, sign the user out, or grant tool permissions. It affects Grok launches outside Hovel too, until removed. The original file was backed up with mode 0600 as `~/.grok/config.toml.hovel-backup-20261028T223456Z-29674`. The update checked for concurrent edits and verified that the parsed configuration changed only by this section.

Two no-prompt native checks passed using the original home/config: personal OIDC subscription authentication plus session creation, and missing-credential rejection with no client request. Both verified that the runtime left the config byte-for-byte unchanged. No credential contents were opened or copied.

This resolves the local work-auth decision, not full work acceptance. Subsequent [protocol checks](work-1.0.70.json) passed real command/edit allow-once and deny behavior plus foreground command cancellation. Work orchestration, native dialogs, pending approvals and close/profile/background cleanup remain required before enabling work. Other installations still need a verified OIDC pin and configuration checks. Hovel does not automatically edit their config. Companion chat already pins its disposable configuration and needs no persistent change.

To undo this local setting, remove only the added `[auth]` section if it still contains just `preferred_method = "oidc"`. Do not replace the whole file from the backup after making later configuration changes.
