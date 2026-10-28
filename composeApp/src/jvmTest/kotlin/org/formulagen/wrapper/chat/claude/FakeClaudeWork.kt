package org.formulagen.wrapper.chat.claude

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.json.*

/**
 * A fake working `claude -p` in its own temp folder: a long-lived process that answers
 * `initialize` (with an account email that must never leave native code) and `interrupt`, and for
 * each user message plays the next turn of the scenario. A turn is a list of steps:
 * `{"emit": line}` prints a line (`SESSION_ID` replaced), `{"ask": control_request}` prints a
 * permission request and waits for Hovel's answer, `{"wait_interrupt": n}` waits for the n-th
 * interrupt, `{"cancel": id}` withdraws a request, `{"exit": code}` ends the process. Every stdin
 * line is recorded in `stdin.jsonl`, and each start's argv, environment and cwd in `calls.jsonl`.
 */
internal class FakeClaudeWork {
    val dir: Path = Files.createTempDirectory("fake-claude-work")
    val executable: Path = dir.resolve("claude")

    init {
        Files.writeString(executable, """
            #!/usr/bin/python3
            import json, os, sys, threading, time, queue
            here = os.path.dirname(os.path.abspath(__file__))
            scenario = json.load(open(os.path.join(here, "work-scenario.json")))
            turns = scenario["turns"]
            args = sys.argv[1:]
            session = next((args[i + 1] for i, a in enumerate(args) if a in ("--session-id", "--resume")), "no-session")
            with open(os.path.join(here, "calls.jsonl"), "a") as f: f.write(json.dumps({"argv": args, "env": dict(os.environ), "cwd": os.getcwd(), "started": time.time()}) + "\n")
            if "--resume" in args and session in scenario.get("missing", []):
                sys.stdout.write(json.dumps({"type": "result", "subtype": "error_during_execution", "is_error": True,
                    "errors": ["No conversation found with session ID: " + session]}) + "\n"); sys.stdout.flush(); sys.exit(1)
            lock = threading.Lock(); cond = threading.Condition(); answers = {}; interrupts = [0]; users = queue.Queue()
            def out(o):
                with lock: sys.stdout.write(json.dumps(o).replace("SESSION_ID", session) + "\n"); sys.stdout.flush()
            def reader():
                for line in sys.stdin:
                    with open(os.path.join(here, "stdin.jsonl"), "a") as f: f.write(line)
                    m = json.loads(line); t = m.get("type")
                    if t == "control_request":
                        sub = m["request"]["subtype"]
                        if sub == "initialize":
                            out({"type": "control_response", "response": {"subtype": "success", "request_id": m["request_id"],
                                 "response": {"commands": [], "account": {"email": "player@example.test", "subscriptionType": "max"}}}})
                        elif sub == "interrupt":
                            with cond: interrupts[0] += 1; cond.notify_all()
                            out({"type": "control_response", "response": {"subtype": "success", "request_id": m["request_id"], "response": {}}})
                    elif t == "control_response":
                        with cond: answers[m["response"]["request_id"]] = m; cond.notify_all()
                    elif t == "user": users.put(m)
                users.put(None)
            threading.Thread(target=reader, daemon=True).start()
            index = 0
            while True:
                m = users.get()
                if m is None: break
                steps = turns[index] if index < len(turns) else []
                index += 1
                for step in steps:
                    if "emit" in step: out(step["emit"])
                    elif "ask" in step:
                        out(step["ask"]); rid = step["ask"]["request_id"]
                        with cond: cond.wait_for(lambda: rid in answers, timeout=20)
                    elif "wait_interrupt" in step:
                        with cond: cond.wait_for(lambda: interrupts[0] >= step["wait_interrupt"], timeout=20)
                    elif "cancel" in step: out({"type": "control_cancel_request", "request_id": step["cancel"]})
                    elif "exit" in step: sys.stdout.flush(); os._exit(step["exit"])
            with open(os.path.join(here, "exits.jsonl"), "a") as f: f.write(json.dumps({"session": session, "ended": time.time()}) + "\n")
        """.trimIndent())
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
        turns()
    }

    /** The turns the next process plays, in order; resuming a session in [missing] fails as Claude Code does for one it does not have. */
    fun turns(vararg turns: List<JsonObject>, missing: List<String> = emptyList()) {
        Files.writeString(dir.resolve("work-scenario.json"), buildJsonObject {
            put("turns", JsonArray(turns.map { JsonArray(it) })); putJsonArray("missing") { missing.forEach { add(it) } }
        }.toString())
    }

    /** When each process that ended on its own (end of input) ended. */
    fun exits(): List<JsonObject> = read("exits.jsonl")

    fun calls(): List<JsonObject> = read("calls.jsonl")
    fun stdin(): List<JsonObject> = read("stdin.jsonl")

    private fun read(name: String) = dir.resolve(name).takeIf { Files.exists(it) }
        ?.let { Files.readAllLines(it).filter(String::isNotBlank).map { line -> Json.parseToJsonElement(line).jsonObject } }.orEmpty()

    companion object {
        fun emit(line: String) = buildJsonObject { put("emit", Json.parseToJsonElement(line)) }
        fun emit(line: JsonObject) = buildJsonObject { put("emit", line) }
        fun ask(request: JsonObject) = buildJsonObject { put("ask", request) }
        fun waitInterrupt(count: Int = 1) = buildJsonObject { put("wait_interrupt", count) }
        fun cancel(requestId: String) = buildJsonObject { put("cancel", requestId) }
        fun exit(code: Int) = buildJsonObject { put("exit", code) }
    }
}
