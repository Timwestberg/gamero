package org.formulagen.wrapper.brain

import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.formulagen.wrapper.bridge.*

internal fun brainId() = UUID.randomUUID().toString()

/** A Writer and an Editor on one model, optionally in a two-step workflow that runs them in that order. */
internal fun testConfiguration(workflow: Boolean = false): BrainConfiguration {
    val binding = BrainBinding(RuntimeId.CODEX, "test-model", ModelEffort.HIGH)
    val roles = listOf("Writer", "Editor").mapIndexed { index, name -> SpecialistRole(brainId(), name, "Instructions for $name", MemberMode.TALK, binding, index) }
    val steps = roles.map { WorkflowStep(brainId(), it.name, it.id, "Do ${it.name}", "Prior artifact", "Result", false, null, null) }
    return BrainConfiguration(1, CoordinatorRole(brainId(), binding, "Help the user."), roles,
        if (workflow) BrainWorkflow(brainId(), "Writing", true, steps, emptyList()) else null)
}

/**
 * `brain.[method]` with [args] as the router runs it: checked and decoded in the contract's form, answered by this
 * capability and encoded with the contract's nulls. Arguments the router would reject as `invalid` throw.
 */
internal suspend fun BrainCapability.call(method: String, args: JsonObject): JsonElement {
    val handler = BrainMethods.binder.handlers.single { it.method.name == "brain.$method" }.bind(this)
    require(handler.accepts(args)) { "brain.$method rejects $args" }
    return handler.invoke(args)
}
