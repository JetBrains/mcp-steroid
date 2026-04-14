/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.promptgen

import com.jonnyzzz.mcpSteroid.prompts.IdeFilter
import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.buildCodeBlock
import java.io.File

/**
 * A Kotlin code block together with its fence annotation metadata.
 */
data class KtBlockWithMeta(val metadata: FenceMetadata, val code: String)

/**
 * Extracts all ` ```kotlin ``` ` blocks from a markdown file's content,
 * including any `[...]` fence annotations.
 *
 * Returns the body of each block (without the fence lines themselves) and its
 * [FenceMetadata], in order.
 */
fun extractKotlinBlocksWithMetadata(content: String): List<KtBlockWithMeta> {
    val lines = content.lines()
    val blocks = mutableListOf<KtBlockWithMeta>()
    var inBlock = false
    var currentMeta = FenceMetadata.DEFAULT
    val current = StringBuilder()
    for (line in lines) {
        when {
            !inBlock && line.trimStart().startsWith("```kotlin") -> {
                inBlock = true
                current.clear()
                val bracket = extractFenceBracket(line)
                currentMeta = if (bracket != null) FenceMetadata.parse(bracket) else FenceMetadata.DEFAULT
            }
            inBlock && line.trimStart() == "```" -> {
                blocks += KtBlockWithMeta(currentMeta, current.toString())
                inBlock = false
            }
            inBlock -> current.appendLine(line)
        }
    }
    return blocks
}

/**
 * Extracts all ` ```kotlin ``` ` blocks from a markdown file's content.
 *
 * Returns the body of each block (without the fence lines themselves), in order.
 * Backward-compatible wrapper around [extractKotlinBlocksWithMetadata].
 */
fun extractKotlinBlocks(content: String): List<String> =
    extractKotlinBlocksWithMetadata(content).map { it.code }

private val junitTestAnnotation = ClassName("org.junit.jupiter.api", "Test")
private val junitAssertions = ClassName("org.junit.jupiter.api", "Assertions")

fun PromptGenerationContext.generatePromptClazzTest(
    clazz: GeneratedPromptClazz,
) {
    val classType = run {
        ClassName(clazz.clazzName.packageName, clazz.clazzName.simpleName + "Test")
    }

    val testFuncSpec = FunSpec.builder("testReadResource")
        .addAnnotation(junitTestAnnotation)
        .returns(Unit::class)
        .addCode(buildCodeBlock {
            add("val content = %T(%S).readText().replace(%S, %S)\n", File::class.asClassName(), clazz.src.absolutePath, "\r\n", "\n")
            add("%T.assertEquals(content, %T().readPrompt())", junitAssertions, clazz.clazzName)
        })
        .build()

    val testTypeSpec = TypeSpec.classBuilder(classType)
        .addFunction(testFuncSpec)
        .build()

    val testFileSpec = FileSpec.builder(classType)
        .addFileComment("GENERATED FILE - DO NOT EDIT")
        .addType(testTypeSpec)
        .build()

    writeTestClazz(testFileSpec, classType)
}


/**
 * Generates a test class for an article that verifies [description] can be read,
 * [readPayload] produces non-empty content, and parts are accessible.
 */
fun PromptGenerationContext.generateArticleReadTest(
    article: GeneratedArticleClazz,
) {
    val articleClassName = article.clazzName
    val baseStem = articleClassName.simpleName.removeSuffix("PromptArticle")
    val classType = ClassName(articleClassName.packageName, "${baseStem}PromptArticleReadTest")

    // Parse source file to extract expected title and description
    val promptArticle = article.article
    val expectedTitle: String
    val expectedDescription: String
    if (promptArticle != null) {
        val parts = parseNewFormatArticleParts(promptArticle.payload.content)
        expectedTitle = parts.title
        expectedDescription = parts.description
    } else {
        expectedTitle = ""
        expectedDescription = ""
    }

    val promptsContextClass = PromptsContext::class.asClassName()
    val methods = mutableListOf<FunSpec>()

    // Test title matches source file
    if (expectedTitle.isNotEmpty()) {
        methods += FunSpec.builder("testTitleMatchesSource")
            .addAnnotation(junitTestAnnotation)
            .addKdoc("Verifies title matches source file for %L.", article.path)
            .returns(Unit::class)
            .addCode(buildCodeBlock {
                addStatement(
                    "%T.assertEquals(%S, %T().title.readPrompt())",
                    junitAssertions, expectedTitle, articleClassName,
                )
            })
            .build()
    }

    // Test description matches source file
    if (expectedDescription.isNotEmpty()) {
        methods += FunSpec.builder("testDescriptionMatchesSource")
            .addAnnotation(junitTestAnnotation)
            .addKdoc("Verifies description matches source file for %L.", article.path)
            .returns(Unit::class)
            .addCode(buildCodeBlock {
                addStatement(
                    "%T.assertEquals(%S, %T().description.readPrompt())",
                    junitAssertions, expectedDescription, articleClassName,
                )
            })
            .build()
    }

    // Test readPayload structure with IDEA context
    methods += FunSpec.builder("testReadPayloadOnIdea")
        .addAnnotation(junitTestAnnotation)
        .addKdoc("Verifies readPayload starts with title+description for IDEA context.", articleClassName)
        .returns(Unit::class)
        .addCode(buildCodeBlock {
            addStatement("val context = %T(%S, 253)", promptsContextClass, "IU")
            addStatement("val payload = %T().readPayload(context)", articleClassName)
            if (expectedTitle.isNotEmpty()) {
                addStatement(
                    "%T.assertTrue(payload.startsWith(%S), %S + payload.take(80))",
                    junitAssertions,
                    "$expectedTitle\n\n$expectedDescription\n\n",
                    "readPayload must start with title and description for ${article.path}: ",
                )
            } else {
                addStatement(
                    "%T.assertTrue(payload.isNotEmpty(), %S)",
                    junitAssertions,
                    "readPayload must not be empty for ${article.path} (IDEA)",
                )
            }
        })
        .build()

    // Test readPayload structure with Rider context
    methods += FunSpec.builder("testReadPayloadOnRider")
        .addAnnotation(junitTestAnnotation)
        .addKdoc("Verifies readPayload starts with title+description for Rider context.", articleClassName)
        .returns(Unit::class)
        .addCode(buildCodeBlock {
            addStatement("val context = %T(%S, 253)", promptsContextClass, "RD")
            addStatement("val payload = %T().readPayload(context)", articleClassName)
            if (expectedTitle.isNotEmpty()) {
                addStatement(
                    "%T.assertTrue(payload.startsWith(%S), %S + payload.take(80))",
                    junitAssertions,
                    "$expectedTitle\n\n$expectedDescription\n\n",
                    "readPayload must start with title and description for ${article.path}: ",
                )
            } else {
                addStatement(
                    "%T.assertTrue(payload.isNotEmpty(), %S)",
                    junitAssertions,
                    "readPayload must not be empty for ${article.path} (Rider)",
                )
            }
        })
        .build()

    val testTypeSpec = TypeSpec.classBuilder(classType)
        .addFunctions(methods)
        .build()

    val testFileSpec = FileSpec.builder(classType)
        .addFileComment("GENERATED FILE - DO NOT EDIT")
        .addType(testTypeSpec)
        .build()

    writeTestClazz(testFileSpec, classType)
}

/**
 * IDE configurations for KtBlock compilation tests.
 * Each entry defines the test method suffix and the base class method to call.
 */
private data class IdeTestConfig(
    val suffix: String,
    val methodName: String,
    val productCode: String,
)

private val IDE_TEST_CONFIGS = listOf(
    IdeTestConfig("OnIdea", "compileKtBlockOnIdea", "IU"),
    IdeTestConfig("OnRider", "compileKtBlockOnRider", "RD"),
    IdeTestConfig("OnClion", "compileKtBlockOnClion", "CL"),
    IdeTestConfig("OnPycharm", "compileKtBlockOnPycharm", "PY"),
    IdeTestConfig("OnIdeaEap", "compileKtBlockOnIdeaEap", "IU"),
    IdeTestConfig("OnRiderEap", "compileKtBlockOnRiderEap", "RD"),
    IdeTestConfig("OnClionEap", "compileKtBlockOnClionEap", "CL"),
    IdeTestConfig("OnPycharmEap", "compileKtBlockOnPycharmEap", "PY"),
)

/**
 * Generates a single compilation test class per new-format `.md` article, with one
 * test method per ` ```kotlin ``` ` block per matching IDE.
 *
 * Called after [generateArticleClazz] so the article's generated class (with its
 * `ktBlock000`, `ktBlock001`, … properties) already exists.
 *
 * For blocks with no fence annotation, generates test methods for all 3 IDEs (IDEA, Rider, CLion).
 * For blocks with a specific annotation (e.g., `[RD]`), generates only for matching IDEs.
 *
 * Generates a class named `{ArticleStem}KtBlocksCompilationTest` with methods:
 *   `testBlock000CompilesOnIdea()`, `testBlock000CompilesOnRider()`, …
 *
 * These tests are written to [testOutputRoot] and extend
 * [KtBlockCompilationTestBase] (JUnit 5, no IntelliJ test framework).
 */
fun PromptGenerationContext.generateMdKtBlockCompilationTests(
    article: GeneratedArticleClazz,
) {
    val promptArticle = article.article ?: return

    val parts = parseNewFormatArticleParts(promptArticle.payload.content)
    val blocks = parts.ktBodyParts
    if (blocks.isEmpty()) return

    val articleClassName = article.clazzName
    val baseStem = articleClassName.simpleName.removeSuffix("PromptArticle")
    val classType = ClassName(
        articleClassName.packageName,
        "${baseStem}KtBlocksCompilationTest",
    )

    // Article-level root filter restricts which IDEs are applicable for all blocks
    val rootFilter = parts.rootFilter
    val articleIdes = when (rootFilter) {
        is IdeFilter.All -> IDE_TEST_CONFIGS
        is IdeFilter.Ide -> if (rootFilter.productCodes.isNotEmpty()) {
            IDE_TEST_CONFIGS.filter { it.productCode in rootFilter.productCodes }
        } else IDE_TEST_CONFIGS
        else -> IDE_TEST_CONFIGS
    }

    val testMethods = mutableListOf<FunSpec>()

    for ((index, block) in blocks.withIndex()) {
        val blockIndex = index.toString().padStart(3, '0')
        val meta = block.metadata

        // Determine which IDEs this block should be tested against
        // Block-level annotation further restricts from article-level IDEs
        val targetIdes = if (meta.isDefault) {
            // No block annotation → use article-level IDEs
            articleIdes
        } else {
            // Filter to matching IDEs based on block's product codes
            val codes = meta.productCodes
            if (codes.isEmpty()) {
                // Version-only constraint → use article-level IDEs
                articleIdes
            } else {
                articleIdes.filter { it.productCode in codes }
            }
        }

        for (ide in targetIdes) {
            testMethods += FunSpec.builder("testBlock${blockIndex}Compiles${ide.suffix}")
                .addAnnotation(junitTestAnnotation)
                .addKdoc("Source: %L, block #%L (%L)", article.path, index, ide.productCode)
                .returns(Unit::class)
                .addStatement("${ide.methodName}(%T().ktBlock${blockIndex})", articleClassName)
                .build()
        }
    }

    if (testMethods.isEmpty()) return

    val testTypeSpec = TypeSpec.classBuilder(classType)
        .superclass(ClassName.bestGuess("com.jonnyzzz.mcpSteroid.prompts.KtBlockCompilationTestBase"))
        .addFunctions(testMethods)
        .build()

    val testFileSpec = FileSpec.builder(classType)
        .addFileComment("GENERATED FILE - DO NOT EDIT")
        .addType(testTypeSpec)
        .build()

    writeTestClazz(testFileSpec, classType)
}
