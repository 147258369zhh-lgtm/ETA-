package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object EtaAppControlToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "eta_control",
                description = "Discover and operate Eta itself through stable capability ids. Use list/search before guessing. " +
                    "execute opens real app screens or changes settings; get reads current values. Destructive or state-changing " +
                    "capabilities marked CONFIRM open an in-app user approval dialog before execution.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "action",
                                JSONObject()
                                    .put("type", "string")
                                    .put("enum", JSONArray(listOf("list", "search", "get", "execute"))),
                            )
                            .put("id", JSONObject().put("type", "string"))
                            .put("query", JSONObject().put("type", "string"))
                            .put("category", JSONObject().put("type", "string"))
                            .put("parameters", JSONObject().put("type", "object")),
                    )
                    .put("required", JSONArray().put("action")),
            ),
        )
    }
}
