package com.tucontrol.domain.repository

import com.tucontrol.domain.model.CommandPlan

/** Turns a natural-language phrase (Spanish) into executable TV actions. */
fun interface CommandInterpreter {
    suspend fun interpret(phrase: String): CommandPlan
}
