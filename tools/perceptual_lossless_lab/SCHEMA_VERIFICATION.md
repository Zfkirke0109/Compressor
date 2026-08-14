# Host schema verification

Verified on 2026-08-14 against current first-party documentation and the installed Windows hosts. Current official documentation overrides this snapshot if the formats change.

## Claude Code

Tested host: Claude Code `2.1.228` (`win32-x64`, commit `4a2077e9c396`); `claude doctor` reported no issue.

Sources:

- <https://code.claude.com/docs/en/plugins>
- <https://code.claude.com/docs/en/plugins-reference>
- <https://code.claude.com/docs/en/skills>
- <https://code.claude.com/docs/en/sub-agents>
- <https://code.claude.com/docs/en/hooks>
- <https://code.claude.com/docs/en/mcp>

The package uses `.claude-plugin/plugin.json`, default `skills/`, `agents/`, and `hooks/hooks.json` discovery, native namespaced Skills, and command-hook `args`. All four agents preload the namespaced constitution Skill. The read-only judge declares only `Read`, `Glob`, and `Grep`; the three executable roles also declare `Bash` and `PowerShell`, explicitly documented as guidance plus record-contract enforcement rather than a host-wide write sandbox.

Installed strict validation differs from some documentation examples: this host rejects a manifestless root and strict validation requires `version` and `author`. The package includes repository-grounded values. `claude plugin validate plugins/claude/compressor-pl-lab --strict` passed. A process-scoped `--bare --plugin-dir ... --no-session-persistence` load was also attempted, but the host stopped before loading because its OAuth session had expired; `claude doctor` confirmed that the installation itself was healthy and that Claude account authentication was inactive. Runtime loading is therefore `BLOCKED` on sign-in, not reported as passed.

## OpenAI ChatGPT/Codex

Tested host: `codex-cli 0.146.1`; Codex Windows app `26.803.10989.0`. The doctor reported CLI `0.147.0` available; no update was performed during this task.

Sources:

- <https://developers.openai.com/plugins/build/plugins>
- <https://developers.openai.com/plugins/build/skills>
- <https://learn.chatgpt.com/docs/build-skills>
- <https://developers.openai.com/plugins/concepts/plugins>
- <https://learn.chatgpt.com/docs/hooks>
- <https://learn.chatgpt.com/docs/agent-configuration/subagents>
- <https://learn.chatgpt.com/docs/plugins>

The package uses `.codex-plugin/plugin.json`, default `skills/`, and default `hooks/hooks.json`. It intentionally has no plugin-level agents, `.mcp.json`, `.app.json`, or manifest `hooks` key. Role separation is provided by focused Skills plus the common contracts; `agents/openai.yaml` is UI/invocation metadata, not a custom subagent definition.

The installed cached `validate_plugin.py` rejects an otherwise documented top-level `hooks` field, while Codex discovers default `hooks/hooks.json`; the package uses that compatibility-superset layout. It also enforces final-directory UI limits and square logo/composer assets. The final package passed that preflight as a skills-only plugin with 8 Skills and 136 entries. The only warning requests confirmation that the package contains no internal-only public capabilities; the package inventory and claim tests provide that review.

The installed CLI has marketplace add/list/remove rather than a plugin validate subcommand. A fresh disposable `CODEX_HOME` smoke added the repository root as marketplace `compressor-local`, listed and installed `compressor-pl-lab@compressor-local`, and confirmed `$compressor-pl-lab:pl-calibrate` in `codex debug prompt-input`. The installed cached Stop wrapper rejected an unsupported success claim and created no Python bytecode. This did not change the real user plugin configuration or persist a Codex session.

Codex invokes Skills as `$compressor-pl-lab:pl-calibrate`, `$compressor-pl-lab:pl-benchmark`, and `$compressor-pl-lab:pl-investigate`. ChatGPT uses the `@` Skill picker. ChatGPT does not execute Codex hooks and local package loading alone cannot grant it access to this checkout, an Android device, or host binaries.

No separate ChatGPT Windows application was installed on the verification host. ChatGPT compatibility is therefore limited to schema/Skill inspection and the documented surface boundary; no ChatGPT runtime-load claim is made.
