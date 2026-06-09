package org.jetbrains.koog.graph.checker

import org.jetbrains.koog.graph.checker.fir.KoogFirCheckersExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

class SimplePluginRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +::KoogFirCheckersExtension
    }
}
