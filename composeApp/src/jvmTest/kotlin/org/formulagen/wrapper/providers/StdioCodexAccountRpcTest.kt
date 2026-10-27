package org.formulagen.wrapper.providers

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.json.*
import kotlin.test.*

class StdioCodexAccountRpcTest {
    @Test fun subprocessExitFailsInitializationInsteadOfClaimingAnAccount() {
        val root = Files.createTempDirectory("hovel-codex-exit")
        try {
            val runtime = CodexRuntime(java.nio.file.Path.of("/usr/bin/false"), root.resolve("private"))
            runtime.prepare()
            assertFails { StdioCodexAccountRpc(runtime) }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun correlatesResponsesHandlesNotificationsAndRejectsUnknownMethods() {
        val root = Files.createTempDirectory("hovel-codex-rpc")
        val executable = root.resolve("fake-codex")
        Files.writeString(executable, """
            #!/usr/bin/python3
            import sys, json
            for line in sys.stdin:
                request = json.loads(line)
                if 'id' not in request: continue
                if request['method'] == 'account/login/start':
                    print(json.dumps({'method':'account/login/completed','params':{'loginId':'login-fixture','success':False}}), flush=True)
                result = {'account':None} if request['method'] == 'account/read' else {}
                print(json.dumps({'id':request['id'],'result':result}), flush=True)
        """.trimIndent())
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
        val runtime = CodexRuntime(executable, root.resolve("private"))
        runtime.prepare()
        try {
            StdioCodexAccountRpc(runtime).use { rpc ->
                assertEquals(JsonNull, rpc.call("account/read")["account"])
                rpc.call("account/login/start")
                assertEquals(false, rpc.loginResult("login-fixture"))
                assertNull(rpc.loginResult("another-login"))
                assertFails { rpc.call("turn/start") }
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun chatTransportRejectsServerRequestsWithoutApprovingTools() {
        val root = Files.createTempDirectory("hovel-codex-refusal")
        val executable = root.resolve("fake-codex")
        Files.writeString(executable, """
            #!/usr/bin/python3
            import sys, json
            waiting = None
            for line in sys.stdin:
                request = json.loads(line)
                if 'method' not in request:
                    assert request['id'] == 'tool-request'
                    assert request['error']['code'] == -32601
                    print(json.dumps({'id':waiting,'result':{'rejected':True}}), flush=True)
                elif request['method'] == 'account/read':
                    waiting = request['id']
                    print(json.dumps({'id':'tool-request','method':'item/commandExecution/requestApproval','params':{}}), flush=True)
                elif 'id' in request:
                    print(json.dumps({'id':request['id'],'result':{}}), flush=True)
        """.trimIndent())
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
        val runtime = CodexRuntime(executable, root.resolve("private")); runtime.prepare()
        val notifications = java.util.concurrent.CopyOnWriteArrayList<String>()
        try {
            StdioCodexAccountRpc(runtime, onNotification = { method, _ -> notifications += method }).use { rpc ->
                assertEquals(JsonPrimitive(true), rpc.call("account/read")["rejected"])
                assertContains(notifications, "request/refused")
            }
        } finally { root.toFile().deleteRecursively() }
    }

}
