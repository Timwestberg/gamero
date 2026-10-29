package org.formulagen.wrapper.chat.grok

import java.nio.file.Files

/** Disposable protocol peer for transport integration tests, with no installed runtime or network. */
internal class FakeGrokAcp(private val scenario: String = "normal") : AutoCloseable {
    val root = Files.createTempDirectory("hovel-fake-grok")
    private val executable = root.resolve("peer.py")

    init {
        Files.writeString(executable, """
            import json, sys, time, subprocess
            mode = sys.argv[1]
            waiting = None
            held = []
            def emit(value):
                print(json.dumps(dict(jsonrpc='2.0', **value)), flush=True)
            for line in sys.stdin:
                request = json.loads(line)
                assert request.get('jsonrpc') == '2.0'
                method = request.get('method')
                rid = request.get('id')
                if method == 'initialize':
                    if mode == 'exit': sys.exit(0)
                    if mode == 'oversized':
                        print('x' * 300000, flush=True)
                        continue
                    if mode == 'malformed':
                        print('{not-json}', flush=True)
                        continue
                    if mode == 'wrong-envelope':
                        print(json.dumps({'id':rid, 'result':{}}), flush=True)
                        continue
                    if mode == 'child':
                        child = subprocess.Popen(['/bin/sleep', '60'])
                        with open(sys.argv[2], 'w') as f: f.write(str(child.pid))
                    emit({'id':rid, 'result':{'protocolVersion':1,'authMethods':[{'id':'grok.com'}], 'agentCapabilities':{'loadSession':True}}})
                    if mode == 'blocked-stdin': time.sleep(60)
                elif method == 'session/cancel':
                    assert 'id' not in request
                    emit({'id':waiting,'result':{'stopReason':'cancelled'}})
                elif method == 'session/new':
                    if mode == 'reverse':
                        held.append(request)
                        if len(held) == 2:
                            for item in reversed(held): emit({'id':item['id'],'result':{'sessionId':item['params']['label']}})
                    else: emit({'id':rid,'result':{'sessionId':'opaque/session'}})
                elif method == 'session/prompt':
                    waiting = rid
                    if mode in ('watcher', 'unknown-response', 'malformed-watcher', 'numeric-string'):
                        wid = 'skills-reload' if mode in ('watcher','malformed-watcher') else ('foreign' if mode == 'unknown-response' else str(rid))
                        emit({'id':wid, 'result':{'result':{'reloaded':1 if mode != 'malformed-watcher' else '1'}}})
                    if mode == 'error':
                        emit({'id':rid,'error':{'code':-32000,'message':'secret-provider-text','data':{'token':'secret'}}})
                    elif mode == 'hang':
                        emit({'method':'test/prompt-received','params':{}})
                        continue
                    elif mode == 'native-permission':
                        raw = {'command':'echo hello','description':'Run example'}
                        tool = {'toolCallId':'tool-1','title':'Unrelated title','rawInput':raw,
                                '_meta':{'x.ai/tool':{'version':1,'name':'Shell','label':'Command','kind':'execute',
                                                    'namespace':'grok_build','read_only':False,'input':raw}}}
                        emit({'id':'opaque-permission','method':'session/request_permission',
                              'params':{'sessionId':'opaque/session','toolCall':tool,'options':[
                                  {'optionId':'allow-23','kind':'allow_once','name':'Allow once'},
                                  {'optionId':'deny-17','kind':'reject_once','name':'Deny'},
                                  {'optionId':'forever','kind':'allow_always','name':'Remember'}]}})
                    elif mode in ('permission','unsupported'):
                        emit({'id':'opaque-permission','method':'session/request_permission' if mode == 'permission' else 'fs/write_text_file',
                              'params':{'sessionId':'opaque/session','options':[{'optionId':'deny-17','kind':'reject_once'}]}})
                    else:
                        emit({'method':'session/update','params':{'sessionId':'opaque/session','update':{'sessionUpdate':'agent_message_chunk','content':{'type':'text','text':'Hello'}}}})
                        emit({'id':rid,'result':{'stopReason':'end_turn'}})
                elif method is None:
                    assert rid == 'opaque-permission'
                    emit({'id':waiting,'result':{'permissionReply':request}})
        """.trimIndent())
    }

    /** Every child uses this fixture only, with a minimal environment. */
    fun builder() = ProcessBuilder("/usr/bin/python3", executable.toString(), scenario, root.resolve("child.pid").toString())
        .directory(root.toFile()).apply { environment().clear(); environment()["PATH"] = "/usr/bin:/bin" }

    override fun close() { root.toFile().deleteRecursively() }
}
