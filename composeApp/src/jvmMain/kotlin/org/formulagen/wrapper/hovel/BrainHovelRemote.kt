package org.formulagen.wrapper.hovel

import kotlinx.serialization.json.*
import org.formulagen.wrapper.companion.*
import java.util.UUID

/**
 * Concrete JSON adapter for fixture-backed Brain service ports. Inject a transport scoped to the approved host.
 * Production HovelHttpTransport deliberately rejects these unmounted routes; no capability/activation is advertised.
 * Server ingress must authenticate its existing host credential and bind task/claim ownership before enabling them.
 */
class BrainHovelRemote(private val transport: HovelTransport) : BrainWorkerRemote {
    override fun admit(assignment: BrainCodexAssignment, execution: ExecutionRecord) {
        val response = transport.post("brain/admit", buildJsonObject {
            put("requestId", operationId(execution.claimId, "admit"))
            put("characterId", assignment.characterId)
            put("configurationRevision", assignment.configurationRevision)
            put("taskId", assignment.request.id)
            put("dispatchId", assignment.request.id)
        })
        require(response.getValue("taskId").jsonPrimitive.content == assignment.request.id)
        require(response.getValue("characterId").jsonPrimitive.content == assignment.characterId)
        require(response.getValue("configurationRevision").jsonPrimitive.long == assignment.configurationRevision)
        require(response.getValue("dispatchId").jsonPrimitive.content == assignment.request.id)
        require(response.getValue("configuration").jsonObject == assignment.configuration)
        require(response.getValue("accountSelections").jsonObject == assignment.accountSelections)
    }

    override fun outcome(assignment: BrainCodexAssignment, execution: ExecutionRecord): BrainPresentationTicket {
        val state = when (execution.phase) {
            ExecutionPhase.SUCCEEDED -> "succeeded"
            ExecutionPhase.FAILED -> "failed"
            ExecutionPhase.RECONCILIATION_REQUIRED -> "uncertain"
            else -> error("Only durable execution outcomes can be published")
        }
        val response = transport.post("brain/outcome", buildJsonObject {
            put("requestId", operationId(execution.claimId, "outcome"))
            put("characterId", assignment.characterId)
            put("configurationRevision", assignment.configurationRevision)
            put("taskId", assignment.request.id)
            put("execution", buildJsonObject {
                put("dispatchId", assignment.request.id); put("revision", 1); put("state", state)
                put("errorCode", when (state) { "failed" -> JsonPrimitive("codex-execution-failed"); "uncertain" -> JsonPrimitive("execution-reconciliation-required"); else -> JsonNull })
                put("resultRef", assignment.request.id); put("deliverableRefs", JsonArray(emptyList()))
            })
        })
        val result = response.getValue("result").jsonObject
        require(result.getValue("taskId").jsonPrimitive.content == assignment.request.id)
        require(result.getValue("execution").jsonObject.getValue("dispatchId").jsonPrimitive.content == assignment.request.id)
        require(result.getValue("execution").jsonObject.getValue("state").jsonPrimitive.content == state)
        val presentation = result.getValue("presentation").jsonObject
        return BrainPresentationTicket(assignment.request.id, presentation.getValue("attemptId").jsonPrimitive.content,
            response.getValue("revision").jsonPrimitive.long, presentation.getValue("state").jsonPrimitive.content)
    }

    override fun claimPresentation(assignment: BrainCodexAssignment, ticket: BrainPresentationTicket, claimId: String) {
        val response = transport.post("brain/presentation-claim", buildJsonObject {
            put("taskId", ticket.taskId); put("attemptId", ticket.attemptId)
            put("expectedRevision", ticket.revision); put("claimId", claimId)
        })
        require(response.getValue("revision").jsonPrimitive.long == ticket.revision)
        val result = response.getValue("result").jsonObject
        require(result.getValue("taskId").jsonPrimitive.content == assignment.request.id)
        val presentation = result.getValue("presentation").jsonObject
        require(presentation.getValue("attemptId").jsonPrimitive.content == ticket.attemptId)
        require(presentation.getValue("state").jsonPrimitive.content == "pending")
    }

    override fun completePresentation(assignment: BrainCodexAssignment, ticket: BrainPresentationTicket, presentation: ExecutionRecord) {
        require(presentation.request.id == ticket.attemptId && ticket.taskId == assignment.request.id)
        check(presentation.phase != ExecutionPhase.RECONCILIATION_REQUIRED) {
            "Presentation requires local reconciliation; retain its pending claim and forbid a new attempt"
        }
        val result = when {
            presentation.phase == ExecutionPhase.SUCCEEDED && !presentation.result.isNullOrBlank() && presentation.result.length <= 8192 ->
                buildJsonObject { put("state", "succeeded"); put("reply", presentation.result) }
            presentation.phase in setOf(ExecutionPhase.SUCCEEDED, ExecutionPhase.FAILED, ExecutionPhase.RECONCILIATION_REQUIRED) ->
                buildJsonObject { put("state", "failed"); put("errorCode", when (presentation.phase) {
                    ExecutionPhase.RECONCILIATION_REQUIRED -> "presentation-reconciliation-required"
                    ExecutionPhase.SUCCEEDED -> "presentation-output-invalid"
                    else -> "codex-presentation-failed"
                }) }
            else -> error("Only durable presentation outcomes can be published")
        }
        val response = transport.post("brain/presentation", buildJsonObject {
            put("requestId", operationId(presentation.claimId, "presentation")); put("taskId", ticket.taskId)
            put("attemptId", ticket.attemptId); put("expectedRevision", ticket.revision)
            put("claimId", presentation.claimId); put("result", result)
        })
        val saved = response.getValue("result").jsonObject
        require(saved.getValue("taskId").jsonPrimitive.content == assignment.request.id)
        require(saved.getValue("presentation").jsonObject.getValue("attemptId").jsonPrimitive.content == ticket.attemptId)
        require(saved.getValue("presentation").jsonObject.getValue("state") == result.getValue("state"))
    }
}

private fun operationId(claimId: String, kind: String): String {
    require(UUID.fromString(claimId).toString() == claimId)
    return UUID.nameUUIDFromBytes("hovel-brain:$kind:$claimId".toByteArray(Charsets.UTF_8)).toString()
}
