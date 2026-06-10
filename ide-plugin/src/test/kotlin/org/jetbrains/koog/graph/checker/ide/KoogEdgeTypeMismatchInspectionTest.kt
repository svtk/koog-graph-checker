package org.jetbrains.koog.graph.checker.ide

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.TestDataPath
import com.intellij.testFramework.fixtures.BasePlatformTestCase

@TestDataPath("\$CONTENT_ROOT/src/test/testData/inspection")
class KoogEdgeTypeMismatchInspectionTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData/inspection"

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(KoogEdgeTypeMismatchInspection())
        myFixture.copyFileToProject("KoogStubs.kt")
        myFixture.copyFileToProject("KoogStubsExtension.kt")
    }

    fun testNoErrorOnCompatibleEdge() = assertEdgeErrors("koogStrategy.kt", 0)

    fun testErrorOnDirectTypeMismatch() = assertEdgeErrors("koogStrategyEdgeTypeMismatch.kt", 1)

    fun testNoErrorOnValidTransform() = assertEdgeErrors("koogStrategyValidTransform.kt", 0)

    fun testErrorOnTransformTypeMismatch() = assertEdgeErrors("koogStrategyTransformed.kt", 1)

    fun testErrorOnIsInstanceMismatch() = assertEdgeErrors("koogStrategyOnIsInstance.kt", 1)

    fun testErrorOnConditionThenTransformMismatch() =
        assertEdgeErrors("koogStrategyOnConditionTransformed.kt", 1)

    // Runs the inspection on the given file and checks that the number of "Invalid edge:" problems
    // matches the expectation. Using doHighlighting() filtered by our message prefix rather than
    // testHighlighting() makes the tests immune to unrelated environment errors (e.g.
    // MISSING_DEPENDENCY_SUPERCLASS from the mock JDK used in tests).
    private fun assertEdgeErrors(fileName: String, expectedCount: Int) {
        myFixture.configureByFile(fileName)
        val edgeErrors = myFixture.doHighlighting(HighlightSeverity.ERROR)
            .filter { it.description?.startsWith("Invalid edge:") == true }
        assertEquals(
            "Expected $expectedCount 'Invalid edge:' error(s) in $fileName",
            expectedCount,
            edgeErrors.size,
        )
    }
}
