# GitHub Actions storage audit and cleanup plan

## Measured inventory — 27 November 2026

- Tracked repository content: 181 files, approximately 0.9 MB, with **no byte-for-byte duplicate files**.
- Actions artifacts: 122 artifacts, 11,006,135,967 bytes (**10.25 GiB**). API archive digests have no exact duplicate groups; these are primarily repeated versioned build outputs.
- Actions caches: 10 entries, approximately **4.33 GiB**, billed/limited separately from artifact storage. No cache deletion is part of this plan.
- Most artifact bytes are image/runtime archives, not Markdown. Removing unique architecture/component documents would not address the artifact limit.

## Proposed cleanup — awaiting explicit confirmation

Permanently remove **37 superseded build artifacts**, totaling **8,222,612,851 bytes (7.66 GiB)**. Retain roughly **2.59 GiB**. This is deletion of older distinct build versions, not content deduplication, and cannot be undone through GitHub artifact recovery. The exact artifact IDs are recorded in the protected workspace `.deploy/github-artifact-cleanup-plan.json`.

| Superseded artifact category | Count selected | Bytes selected, rounded |
| --- | --- | --- |
| droplet-release | 20 | 3.65 GiB |
| ha-runtime | 6 | 3.08 GiB |
| local-ha-vendor-arm64 | 2 | 0.48 GiB |
| verification-application | 9 | 0.46 GiB |

Preserve all artifacts from verification runs [45927971103](https://github.com/Sahil2237/notification-fanout/actions/runs/45927971103) and [45855313505](https://github.com/Sahil2237/notification-fanout/actions/runs/45855313505), plus the `ha-runtime` bundles from runs 45839195205 and 45375169877 for conservative infrastructure/backup recovery coverage. Preserve all small test/security/digest reports, Git history, current documentation, registry images, database backups and Droplet files. Recheck active runs and protected IDs immediately before deletion.

No production database or service cleanup is implied. Removing an obsolete Actions archive does not stop an already-running container, but removes that archive as a future download source. Keep signed recovery bundles before further pruning below this conservative plan.

## What cleanup can and cannot fix

Deleting artifacts reduces current storage and future accumulation; it does not erase already accrued storage consumption or restore consumed compute allowance. Billing displays can take time to refresh. Keeping 2.59 GiB also does not guarantee the retained storage fits the account's included allowance. No paid plan, spending budget or billing setting is changed here.

GitHub references: [Actions storage billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions), [artifact deletion is irreversible](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/remove-workflow-artifacts). The API inventory is repository-scoped; an account-wide percentage can include other repositories/packages.


## Preventing repeated storage growth

The CI configuration now requests one-day retention for the large verification JAR, application release archive and HA runtime archive. The optional local HA vendor bundle also uses one day. Small test/security reports keep their existing retention. These changes affect future uploads, not existing artifacts. Archive a signed recovery bundle in approved protected storage before it expires; redeploying an older verification run requires its bundle still to exist.

Documentation-only pushes (`**/*.md` or `docs/**` only) are excluded from the full verification/image-packaging workflow. Code/configuration pushes and pull requests retain verification; pull requests are intentionally not path-filtered so existing required-check expectations are preserved. The configuration itself was checked locally and is committed with a CI-skip marker to avoid creating another large artifact set while storage is exhausted. No paid usage allowance is increased.
