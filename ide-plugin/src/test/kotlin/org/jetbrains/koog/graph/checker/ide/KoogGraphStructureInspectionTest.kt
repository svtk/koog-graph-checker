package org.jetbrains.koog.graph.checker.ide

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
 * `compiler-plugin/testData/diagnostics/koogStrategy*.kt` case (spec §2.1–§2.6); both layers build the
 * shared graph model and run the same analysis, so the highlighted messages match the compiler's.
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

    fun testEnumMissingCase() {
        myFixture.configureByFile("koogStrategyEnumMissingCase.kt")
        myFixture.checkHighlighting(true, false, false)
    }

    fun testEnumExhaustive() {
        myFixture.configureByFile("koogStrategyEnumExhaustive.kt")
        myFixture.checkHighlighting(true, false, false)
    }

    fun testBooleanMissingCase() {
        myFixture.configureByFile("koogStrategyBooleanMissingCase.kt")
        myFixture.checkHighlighting(true, false, false)
    }
}
