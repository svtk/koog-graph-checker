package org.jetbrains.koog.graph.checker.ide

import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.openapi.module.Module
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.TestDataPath
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.io.File

/**
 * IDE counterpart of the compiler plugin's structural diagnostics tests. Each fixture mirrors a
 * `compiler-plugin/testData/diagnostics/koogStrategy*.kt` case; both layers build the shared graph
 * model and run the same analysis, so the highlighted messages match the compiler's.
 */
@TestDataPath("\$CONTENT_ROOT/src/test/testData/inspection")
class KoogGraphStructureInspectionTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getTestDataPath(): String = "src/test/testData/inspection"

    override fun getProjectDescriptor() = object : DefaultLightProjectDescriptor() {
        override fun getSdk(): Sdk = JavaSdk.getInstance()
            .createJdk("JDK", System.getProperty("java.home"), false)

        override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
            super.configureModule(module, model, contentEntry)
            System.getProperty("koog.classpath")
                ?.split(File.pathSeparator)
                ?.forEach { jarPath ->
                    val jar = File(jarPath)
                    PsiTestUtil.addLibrary(model, jar.nameWithoutExtension, jar.parent, jar.name)
                }
        }
    }

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(KoogGraphStructureInspection())
        // Koog's builder API is annotated with `@DslMarker`, so the Kotlin plugin paints every DSL call
        // (`strategy`, `edge`, `forwardTo`, `onCondition`, …) with a semantic "DSL style" highlight. Those
        // carry a custom `DSL_TYPE_SEVERITY` that `checkHighlighting` does not recognise as symbol-level
        // coloring, so it flags each one as an unexpected highlight. Drop them so only our diagnostics remain.
        HighlightInfoFilter.EXTENSION_POINT_NAME.point.registerExtension(
            HighlightInfoFilter { info, _ -> info.severity.name != "DSL_TYPE_SEVERITY" },
            testRootDisposable,
        )
    }

    fun testFinishOutgoingEdge() {
        myFixture.configureByFile("koogStrategyFinishOutgoing.kt")
        myFixture.checkHighlighting(true, false, false)
    }

    fun testFinishUnreachable() {
        myFixture.configureByFile("koogStrategyFinishUnreachable.kt")
        myFixture.checkHighlighting(true, false, false)
    }

    fun testDuplicateNodeNames() {
        myFixture.configureByFile("koogStrategyDuplicateNames.kt")
        myFixture.checkHighlighting(true, false, false)
    }

    fun testUnreachableNode() {
        myFixture.configureByFile("koogStrategyUnreachableNode.kt")
        myFixture.checkHighlighting(true, false, false)
    }

    fun testShadowedEdge() {
        myFixture.configureByFile("koogStrategyShadowedEdge.kt")
        myFixture.checkHighlighting(true, false, false)
    }

    fun testDeadEndNode() {
        myFixture.configureByFile("koogStrategyDeadEnd.kt")
        myFixture.checkHighlighting(true, false, false)
    }

    fun testAllConditionalNoFallback() {
        myFixture.configureByFile("koogStrategyAllConditional.kt")
        myFixture.checkHighlighting(true, false, true)
    }

    fun testEnumNonExhaustive() {
        myFixture.configureByFile("koogStrategyEnumNonExhaustive.kt")
        myFixture.checkHighlighting(true, false, false)
    }

    fun testEnumExhaustive() {
        myFixture.configureByFile("koogStrategyEnumExhaustive.kt")
        myFixture.checkHighlighting(true, false, false)
    }

    fun testBooleanNonExhaustive() {
        myFixture.configureByFile("koogStrategyBooleanNonExhaustive.kt")
        myFixture.checkHighlighting(true, false, false)
    }

    fun testOpaqueCondition() {
        myFixture.configureByFile("koogStrategyOpaqueCondition.kt")
        myFixture.checkHighlighting(true, false, false)
    }

    fun testEnumWithFallback() {
        myFixture.configureByFile("koogStrategyEnumWithFallback.kt")
        myFixture.checkHighlighting(true, false, false)
    }
}
