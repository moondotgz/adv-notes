package com.advnotes.app.tools

/** Native tools shipped with the app. Web tools are discovered from assets/tools. */
object BuiltinTools {
    fun all(): List<NotebookTool> = listOf(com.advnotes.app.tools.calculator.CalculatorTool, AiChatTool)
}
