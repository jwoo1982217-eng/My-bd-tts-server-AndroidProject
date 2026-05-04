package com.github.jing332.script.simple

import com.github.jing332.script.runtime.Environment
import com.github.jing332.script.runtime.RhinoScriptRuntime
import com.github.jing332.script.simple.ext.JsExtensions
import java.io.File

class CompatScriptRuntime(val ttsrv: JsExtensions) :
    RhinoScriptRuntime(
        environment = buildEnvironment(ttsrv)
    ) {

    override fun init() {
        super.init()
        globalScope.defineGetter("ttsrv", ::ttsrv)
    }

    companion object {
        private fun buildEnvironment(ttsrv: JsExtensions): Environment {
            val rootDir = ttsrv.context.getExternalFilesDir(null) ?: ttsrv.context.filesDir
            val pluginsDir = File(rootDir, "plugins")
            if (!pluginsDir.exists()) pluginsDir.mkdirs()

            return Environment(
                pluginsDir.absolutePath,
                ttsrv.engineId
            )
        }
    }
}