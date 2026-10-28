package org.formulagen.wrapper.providers

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.json.*

/**
 * A fake `claude` in its own temp folder. `--version` and `auth status` print the scenario's
 * answers; `-p` reads stdin to the end, refuses to run (exit 97) if any `ANTHROPIC_*` or `CLAUDE_*`
 * variable other than the two allowed settings reached it, then replays the scenario's NDJSON with
 * `SESSION_ID` replaced by the session it was given. A `{"__fake":"wait_for_sigint"}` line waits
 * for SIGINT, and `{"__fake":"linger"}` keeps the process alive a moment longer. Every call is
 * recorded with its argv, environment, cwd and stdin, and whether another process was still
 * running on the same session when it started (`overlap`).
 */
internal class FakeClaude {
    val dir: Path = Files.createTempDirectory("fake-claude")
    val executable: Path = dir.resolve("claude")
    val providers: Path = dir.resolve("providers")

    init {
        Files.writeString(executable, """
            #!/usr/bin/python3
            import json, os, signal, sys, time
            here = os.path.dirname(os.path.abspath(__file__))
            scenario = json.load(open(os.path.join(here, "scenario.json")))
            interrupted = []
            signal.signal(signal.SIGINT, lambda *_: interrupted.append(True))
            args = sys.argv[1:]
            def record(**extra):
                call = {"argv": args, "env": dict(os.environ), "cwd": os.getcwd()}
                call.update(extra)
                with open(os.path.join(here, "calls.jsonl"), "a") as f: f.write(json.dumps(call) + "\n")
            if args == ["--version"]:
                record(); print(scenario["version"]); sys.exit(scenario["versionExit"])
            if args[:2] == ["auth", "status"]:
                record(); time.sleep(scenario["authSleep"]); sys.stdout.write(scenario["auth"]); sys.stdout.flush(); sys.exit(scenario["authExit"])
            if "-p" in args:
                session = next((args[i + 1] for i, a in enumerate(args) if a in ("--session-id", "--resume")), "no-session")
                lock = os.path.join(here, "running-" + session)
                overlap = os.path.exists(lock)
                open(lock, "w").close()
                try:
                    record(stdin=sys.stdin.read(), overlap=overlap)
                    allowed = ("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "CLAUDE_CODE_DISABLE_AUTO_MEMORY")
                    if any(k.startswith("ANTHROPIC") or (k.startswith("CLAUDE") and k not in allowed) for k in os.environ): sys.exit(97)
                    for line in open(os.path.join(here, "turn.ndjson")):
                        if line.startswith('{"__fake":"wait_for_sigint"}'):
                            deadline = time.time() + 10
                            while not interrupted and time.time() < deadline: time.sleep(0.01)
                            if interrupted: open(os.path.join(here, "sigint"), "w").write("1")
                            continue
                        if line.startswith('{"__fake":"linger"}'):
                            time.sleep(1.5)
                            continue
                        sys.stdout.write(line.replace("SESSION_ID", session)); sys.stdout.flush()
                    sys.exit(scenario["exit"])
                finally:
                    os.remove(lock)
            record(); sys.exit(2)
        """.trimIndent())
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
        scenario()
    }

    /** What the next calls answer. [turn] is NDJSON text, usually a fixture from [fixture]. */
    fun scenario(
        turn: String = fixture("turn-success.ndjson"),
        exit: Int = 0,
        version: String = "2.1.304 (Claude Code)",
        versionExit: Int = 0,
        auth: String = fixture("auth-status-subscription.json"),
        authExit: Int = 0,
        authSleepSeconds: Double = 0.0,
    ) {
        Files.writeString(dir.resolve("turn.ndjson"), turn)
        Files.writeString(dir.resolve("scenario.json"), buildJsonObject {
            put("exit", exit); put("version", version); put("versionExit", versionExit)
            put("auth", auth); put("authExit", authExit); put("authSleep", authSleepSeconds)
        }.toString())
    }

    fun calls(): List<JsonObject> = dir.resolve("calls.jsonl").takeIf { Files.exists(it) }
        ?.let { Files.readAllLines(it).map { line -> Json.parseToJsonElement(line).jsonObject } }.orEmpty()

    fun turns() = calls().filter { "-p" in it.argv() }

    val interrupted get() = Files.exists(dir.resolve("sigint"))

    /** A runtime whose parent environment carries the variables a child must never see. */
    fun runtime(parent: Map<String, String> = LEAKY_PARENT) = ClaudeRuntime(executable, providers, parent)

    companion object {
        val LEAKY_PARENT: Map<String, String> = System.getenv() + mapOf(
            "ANTHROPIC_API_KEY" to "sk-ant-test", "ANTHROPIC_BASE_URL" to "https://proxy.example.test",
            "CLAUDE_CODE_ENTRYPOINT" to "parent-session", "CLAUDE_CONFIG_DIR" to "/tmp/other-claude",
            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" to "0", "OPENAI_API_KEY" to "sk-test", "HOVEL_SECRET" to "x",
            "HOME" to System.getProperty("user.home"), "USER" to "player", "LOGNAME" to "player", "LANG" to "en_US.UTF-8",
        )

        fun fixture(name: String): String = checkNotNull(FakeClaude::class.java.getResource("/claude/$name")) { name }.readText()
    }
}

internal fun JsonObject.argv(): List<String> = getValue("argv").jsonArray.map { it.jsonPrimitive.content }
internal fun JsonObject.env(): Map<String, String> = getValue("env").jsonObject.mapValues { it.value.jsonPrimitive.content }
