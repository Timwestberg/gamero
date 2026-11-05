package org.formulagen.wrapper.bridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.formulagen.wrapper.conversation.ActivityKind
import org.formulagen.wrapper.conversation.ActivityStatus
import org.formulagen.wrapper.conversation.ActivityStep
import org.formulagen.wrapper.conversation.ChatVoice
import org.formulagen.wrapper.conversation.MemberMode

/** Brain types encode exactly the nulls event-contracts requires, and omit the optional ones ([brainWire]). */
class BrainWireTest {
    private fun id(n: Int) = "10000000-0000-4000-8000-" + n.toString().padStart(12, '0')

    @Test fun optionalNullsNeverReachTheWire() {
        val issue = brainWire(BrainIssue(BrainIssueReason.MISSING_BINDING, roleId = id(1))).jsonObject
        assertEquals(setOf("reason", "roleId"), issue.keys)
        val step = brainWire(ActivityStep(ActivityKind.COMMAND, "npm test", ActivityStatus.FAILED)).jsonObject
        assertFalse("detail" in step)
        val role = brainWire(SpecialistRole(id(2), "Writer", "", MemberMode.TALK, null, 0)).jsonObject
        assertEquals(JsonNull, role.getValue("binding"))
        assertFalse("notice" in brainWire(ChatVoice()).jsonObject)
    }

    private fun step(n: Int, specialist: Int) = """{"id":"${id(n)}","name":"Step $n","specialistId":"${id(specialist)}","instructions":"Do it.",""" +
        """"inputDescription":"","outputDescription":"","askBeforeStarting":false,"sectionId":null,"returnTo":null}"""
    private val coordinator = """{"id":"${id(1)}","binding":null,"instructions":""}"""
    private fun specialist(skills: String = "") = """{"id":"${id(2)}","name":"Writer","instructions":"","mode":"talk","binding":null,"slot":0$skills}"""

    /** A version 1 brain as the contract sends it, with its one [workflow] (or `null`). */
    private fun version1(workflow: String = """{"id":"${id(3)}","name":"Ship","enabled":true,"steps":[${step(4, 2)}],"sections":[]}""") =
        """{"version":1,"coordinator":$coordinator,"specialists":[${specialist()}],"workflow":$workflow}"""

    /** A version 2 brain with two workflows, the second the default, and skills its runs keep but do not use. */
    private val version2 = """{"version":2,"coordinator":$coordinator,"specialists":[${specialist(""","skills":[""" +
        """{"source":"hovel","skillId":"${id(9)}"},{"source":"provider","runtimeId":"claude-code","name":"code-review"}]""")}],""" +
        """"workflows":[{"id":"${id(3)}","name":"Bug fix","enabled":true,"steps":[${step(4, 2)}],"sections":[],"useFor":"Reported bugs","default":false},""" +
        """{"id":"${id(5)}","name":"Feature","enabled":false,"steps":[${step(6, 2)}],"sections":[],"useFor":"New features","default":true}],""" +
        """"skills":[{"id":"${id(9)}","name":"Testing","instructions":"Write tests first.","useWhen":"Changing code"}]}"""

    private fun read(element: JsonElement) = brainJson.decodeFromJsonElement(BrainConfiguration.serializer(), element)
    private fun read(text: String) = read(brainJson.parseToJsonElement(text))
    private fun JsonElement.with(vararg fields: Pair<String, JsonElement>) = JsonObject(jsonObject + fields)

    @Test fun aVersion1BrainReadsItsOneWorkflowAsTheListAndWritesItBackUnchanged() {
        val brain = read(version1())
        val workflow = brain.workflows.single()
        assertEquals(listOf(id(3), "", false), listOf(workflow.id, workflow.useFor, workflow.isDefault))
        assertEquals(listOf(workflow), brain.enabledWorkflows)
        assertEquals(workflow.steps.single(), brain.step(id(4)))
        assertNull(brain.step(id(6)))
        assertEquals(listOf(emptyList<Any>(), emptyList()), listOf(brain.skills, brain.specialists.single().skills))
        assertEquals(brainJson.parseToJsonElement(version1()), brainWire(brain))
        val none = read(version1("null"))
        assertEquals(emptyList(), none.workflows)
        assertEquals(brainJson.parseToJsonElement(version1("null")), brainWire(none))
    }

    @Test fun aVersion2BrainReadsEveryWorkflowAndKeepsItsSkillsUnused() {
        val brain = read(version2)
        assertEquals(listOf("Bug fix" to false, "Feature" to true), brain.workflows.map { it.name to it.isDefault })
        assertEquals(listOf("Reported bugs", "New features"), brain.workflows.map { it.useFor })
        assertEquals(listOf("Bug fix"), brain.enabledWorkflows.map { it.name })
        assertEquals("Step 6", brain.step(id(6))?.name)
        assertEquals(listOf(SkillRef.Hovel(id(9)), SkillRef.Provider(RuntimeId.CLAUDE_CODE, "code-review")), brain.specialists.single().skills)
        assertEquals(listOf("Testing"), brain.skills.map { it.name })
        assertEquals(brainJson.parseToJsonElement(version2), brainWire(brain))
    }

    @Test fun eachVersionRefusesTheOthersFields() {
        val v1 = brainJson.parseToJsonElement(version1())
        val v2 = brainJson.parseToJsonElement(version2)
        val workflow = v1.jsonObject.getValue("workflow")
        val refused = mapOf(
            "version 1 with workflows" to v1.with("workflows" to JsonArray(listOf(workflow))),
            "version 1 with skills" to v1.with("skills" to JsonArray(emptyList())),
            "a version 1 workflow with Use it for" to v1.with("workflow" to workflow.with("useFor" to JsonPrimitive(""))),
            "a version 1 default" to v1.with("workflow" to workflow.with("default" to JsonPrimitive(true))),
            "a version 1 specialist with skills" to v1.with("specialists" to JsonArray(listOf(brainJson.parseToJsonElement(specialist(""","skills":[]"""))))),
            "version 1 without its workflow" to JsonObject(v1.jsonObject - "workflow"),
            "version 2 with a workflow" to v2.with("workflow" to JsonNull),
            "version 2 over its workflow limit" to v2.with("workflows" to JsonArray(List(BrainLimits.WORKFLOWS + 1) { workflow })),
            "an unknown version" to v2.with("version" to JsonPrimitive(3)),
        )
        for ((name, element) in refused) assertFails(name) { read(element) }
        val brain = read(v1)
        assertFails("a version 1 brain holds one workflow") { brain.copy(workflows = brain.workflows + brain.workflows) }
    }
}
