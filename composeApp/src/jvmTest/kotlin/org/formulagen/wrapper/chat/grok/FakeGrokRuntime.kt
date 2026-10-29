package org.formulagen.wrapper.chat.grok

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import org.formulagen.wrapper.providers.grok.GROK_VERIFIED_VERSION

/** Process fixture exercises the actual version, inspection, account, session and streaming boundaries. */
internal class FakeGrokRuntime : AutoCloseable {
    val root = Files.createTempDirectory("fake-grok-runtime")
    private val executable = root.resolve("grok")
    val env = mapOf("HOME" to root.toString(), "GAMERO_GROK_PATH" to executable.toString())
    init {
        mode("normal")
        Files.createDirectories(root.resolve(".grok"))
        Files.writeString(root.resolve(".grok/config.toml"), "[auth]\npreferred_method='oidc'\n")
        Files.writeString(executable, """
            #!/usr/bin/python3
            import json, sys, pathlib, os
            root=pathlib.Path(__file__).parent
            def out(value): print(json.dumps(dict(jsonrpc='2.0',**value)),flush=True)
            if '--version' in sys.argv: print('${GROK_VERIFIED_VERSION}'); sys.exit(0)
            if 'inspect' in sys.argv:
                trust=json.loads((root/'trust.json').read_text()) if (root/'trust.json').exists() else {}
                if '--trust' in sys.argv:
                    with (root/'requests').open('a') as f: f.write('trust\n')
                    trust['trusted']=True; trust['root']=trust.get('rootAfterTrust',trust.get('root')); (root/'trust.json').write_text(json.dumps(trust))
                report=dict(projectTrusted=trust.get('trusted',True),projectInstructions=[],hooks=[],skills=[],plugins=[],mcpServers=[],lspServers=[],
                    configSources={'layers':[{'role':'user','path':str(pathlib.Path(os.environ['GROK_HOME'])/'config.toml')}]},permissions={'managedSettingsExists':False})
                if trust.get('root'): report['projectRoot']=trust['root']
                print(json.dumps(report)); sys.exit(0)
            meta=dict(auth_mode='Oidc',backend_billed=False,is_team_principal=False,is_organization_principal=False,gate=None,subscription_tier='SuperGrok')
            models=dict(currentModelId='grok-test',availableModels=[dict(modelId='grok-test',name='Grok Test')])
            sid='provider/opaque-id'
            waiting=None
            # Work runs in the player's provider home; the account check and talk use isolated ones.
            work=os.environ['GROK_HOME']==str(root/'.grok')
            profile=sys.argv[sys.argv.index('--agent-profile')+1]
            with (root/'pids').open('a') as f: f.write(str(os.getpid())+'\n')
            (root/'agent-environment.json').write_text(json.dumps(dict(os.environ)))
            if work: (root/'work-profile').write_text(profile)
            for line in sys.stdin:
                req=json.loads(line); method=req.get('method'); rid=req.get('id')
                if method is None:
                    with (root/'requests').open('a') as f: f.write('permission/replied\n')
                    continue
                with (root/'requests').open('a') as f: f.write(method+'\n')
                if work:
                    with (root/'work-requests').open('a') as f: f.write(method+'\n')
                mode=(root/'mode').read_text()
                result={}
                if method=='initialize' and work and mode=='repin':
                    pathlib.Path(os.environ['GROK_HOME'],'config.toml').write_text("[auth]\npreferred_method='api_key'\n")
                if method=='initialize': result=dict(protocolVersion=1,authMethods=[{'id':'cached_token'}],agentCapabilities={'loadSession':True},_meta={'modelState':models})
                elif method=='_x.ai/auth/info': result=dict(methodId='cached_token',principalType='user',principalId='fixture-user')
                elif method=='_x.ai/auth/check_subscription': result=dict(authenticated=True,meta=meta)
                elif method=='authenticate': result={'_meta':meta}
                elif method=='session/load' and mode in ['reject-load','reject-load-held']:
                    if mode=='reject-load-held':
                        # Something holds the profile path open as a folder, so the launch cannot remove it.
                        pathlib.Path(profile).unlink(); pathlib.Path(profile).mkdir(); pathlib.Path(profile,'held').write_text('held')
                    out({'id':rid,'error':{'code':-32002,'message':'Session not found'}}); continue
                elif method in ['session/new','session/load']:
                    out({'method':'session/update','params':{'sessionId':sid,'update':{'sessionUpdate':'available_commands_update','_meta':{'tools':['shell'] if mode=='tools' else []}}}})
                    result={'sessionId':sid,'models':models}
                elif method=='session/prompt':
                    if mode=='drop': sys.exit(0)
                    if mode=='wait': waiting=rid; continue
                    if mode=='stuck': continue
                    if mode=='approval':
                        waiting=rid
                        tool=dict(toolCallId='operation',rawInput=dict(variant='Bash',command='printf TEST',description='Test command'),
                            _meta={'x.ai/tool':dict(version=1,namespace='grok_build',kind='execute',name='run_terminal_cmd',label='Run',read_only=False,
                                input=dict(command='printf TEST',description='Test command'))})
                        options=[dict(optionId='yes',kind='allow_once',name='Allow once'),dict(optionId='no',kind='reject_once',name='Deny')]
                        out(dict(id='permission',method='session/request_permission',params=dict(sessionId=sid,toolCall=tool,options=options)))
                        continue
                    out({'method':'session/update','params':{'sessionId':sid,'update':{'sessionUpdate':'agent_thought_chunk','content':{'type':'text','text':'hidden'}}}})
                    out({'method':'session/update','params':{'sessionId':sid,'update':{'sessionUpdate':'agent_message_chunk','content':{'type':'text','text':'Hello'}}}})
                    result={'stopReason':'end_turn'}
                elif method=='session/cancel':
                    if waiting is None: continue
                    rid=waiting; waiting=None; result={'stopReason':'cancelled'}
                else: raise RuntimeError('Unexpected method')
                out({'id':rid,'result':result})
        """.trimIndent())
        executable.toFile().setExecutable(true)
    }
    fun mode(value: String) { Files.writeString(root.resolve("mode"), value) }
    /** Inspection reports the project untrusted with [projectRoot]; `--trust` then reports [rootAfterTrust]. */
    fun untrusted(projectRoot: java.nio.file.Path, rootAfterTrust: java.nio.file.Path = projectRoot) = Files.writeString(root.resolve("trust.json"),
        buildJsonObject { put("trusted", false); put("root", projectRoot.toString()); put("rootAfterTrust", rootAfterTrust.toString()) }.toString())
    fun requests(): List<String> = root.resolve("requests").let { if (Files.exists(it)) Files.readAllLines(it) else emptyList() }
    /** Requests a work agent received, without the isolated account checks' own. */
    fun workRequests(): List<String> = root.resolve("work-requests").let { if (Files.exists(it)) Files.readAllLines(it) else emptyList() }
    /** The agent profile of the last work agent. */
    fun workProfile(): Path = Path.of(Files.readString(root.resolve("work-profile")))
    override fun close() { root.toFile().deleteRecursively() }
}
