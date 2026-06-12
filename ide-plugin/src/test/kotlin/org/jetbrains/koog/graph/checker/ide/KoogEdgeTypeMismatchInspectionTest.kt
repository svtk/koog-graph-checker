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

@TestDataPath("\$CONTENT_ROOT/src/test/testData/inspection")
class KoogEdgeTypeMismatchInspectionTest : LightJavaCodeInsightFixtureTestCase() {

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
        myFixture.enableInspections(KoogEdgeTypeMismatchInspection())
    }

    fun testNoErrorOnCompatibleEdge() {
        myFixture.configureByFile("koogStrategy.kt")
        myFixture.checkHighlighting(false, false, false, true)
    }

    fun testErrorOnDirectTypeMismatch() {
        myFixture.configureByFile("koogStrategyEdgeTypeMismatch.kt")
        myFixture.checkHighlighting(false, false, false, true)
    }

    fun testNoErrorOnValidTransform() {
        myFixture.configureByFile("koogStrategyValidTransform.kt")
        myFixture.checkHighlighting(false, false, false, true)
    }

    fun testErrorOnTransformTypeMismatch() {
        myFixture.configureByFile("koogStrategyTransformed.kt")
        myFixture.checkHighlighting(false, false, false, true)
    }

    fun testErrorOnIsInstanceMismatch() {
        myFixture.configureByFile("koogStrategyOnIsInstance.kt")
        myFixture.checkHighlighting(false, false, false, true)
    }

    fun testErrorOnConditionThenTransformMismatch() {
        myFixture.configureByFile("koogStrategyOnConditionTransformed.kt")
        myFixture.checkHighlighting(false, false, false, true)
    }

    fun testErrorNamesNodesInMessage() {
        myFixture.configureByFile("koogStrategyNamedNodes.kt")
        myFixture.checkHighlighting(false, false, false, true)
    }

    fun testErrorOnConditionOnlyMismatch() {
        myFixture.configureByFile("koogStrategyOnCondition.kt")
        myFixture.checkHighlighting(false, false, false, true)
    }
}
