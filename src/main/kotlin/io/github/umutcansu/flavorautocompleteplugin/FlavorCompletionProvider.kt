package io.github.umutcansu.flavorautocompleteplugin

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import org.jetbrains.kotlin.psi.*
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrMethodCall
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.literals.GrLiteral
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.path.GrMethodCallExpression

class CustomPrefixMatcher(prefix: String) : PrefixMatcher(prefix) {
    override fun prefixMatches(name: String): Boolean {
        return name.startsWith(prefix, ignoreCase = true)
    }

    override fun cloneWithPrefix(newPrefix: String): PrefixMatcher = CustomPrefixMatcher(newPrefix)
}


class FlavorCompletionProvider : CompletionProvider<CompletionParameters>() {

    private val LOG = Logger.getInstance(FlavorCompletionProvider::class.java)

    private val smartInsertHandler = InsertHandler<LookupElement> { context, item ->
        val elementAtCaret = context.file.findElementAt(context.startOffset)

        if (elementAtCaret?.text == "$" && elementAtCaret.parent is KtStringTemplateExpression) {
            context.document.replaceString(
                elementAtCaret.textRange.startOffset,
                elementAtCaret.textRange.endOffset,
                item.lookupString
            )
            return@InsertHandler
        }

        val stringLiteral = PsiTreeUtil.getParentOfType(
            elementAtCaret,
            GrLiteral::class.java,
            KtStringTemplateExpression::class.java
        )

        if (stringLiteral != null) {
            val textRange = stringLiteral.textRange
            val newText = "\"${item.lookupString}\""
            context.document.replaceString(textRange.startOffset, textRange.endOffset, newText)
            context.editor.caretModel.moveToOffset(textRange.startOffset + newText.length)
        } else {
            val document = context.document
            document.replaceString(context.startOffset, context.tailOffset, item.lookupString)
            context.editor.caretModel.moveToOffset(context.startOffset + item.lookupString.length)
        }
    }


    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        resultSet: CompletionResultSet
    ) {
        val flavors = findFlavorsFromAllModules(parameters)

        if (flavors.isNotEmpty()) {
            val customResultSet = resultSet.withPrefixMatcher(CustomPrefixMatcher(resultSet.prefixMatcher.prefix))
            flavors.forEach { flavorName ->
                val lookupElement = LookupElementBuilder.create(flavorName)
                    .withInsertHandler(smartInsertHandler)
                customResultSet.addElement(lookupElement)
            }
        }
    }

    private fun findFlavorsFromAllModules(parameters: CompletionParameters): Set<String> {
        val project = parameters.originalFile.project

        return CachedValuesManager.getManager(project).getCachedValue(project) {
            val allModules = ModuleManager.getInstance(project).modules
            val allFlavors = mutableSetOf<String>()
            val buildFiles = mutableListOf<VirtualFile>()

            for (module in allModules) {
                allFlavors.addAll(getProductFlavorNames(module))
                collectBuildFiles(module, buildFiles)
            }

            val dependencies = mutableListOf<Any>(ProjectRootManager.getInstance(project))
            buildFiles.forEach { vf ->
                PsiManager.getInstance(project).findFile(vf)?.let { dependencies.add(it) }
            }

            CachedValueProvider.Result.create(allFlavors, dependencies)
        }
    }

    private fun collectBuildFiles(module: Module, buildFiles: MutableList<VirtualFile>) {
        for (root in ModuleRootManager.getInstance(module).contentRoots) {
            root.findChild("build.gradle")?.let { buildFiles.add(it) }
            root.findChild("build.gradle.kts")?.let { buildFiles.add(it) }
        }
    }

    // --- Fallback chain ---

    private fun getProductFlavorNames(module: Module): Collection<String> {
        // Try 1: GradleAndroidModel (AS Giraffe+ / 231+)
        tryReflection("com.android.tools.idea.gradle.project.model.GradleAndroidModel", module)
            ?.let { return it }

        // Try 2: AndroidModuleModel (older AS, Arctic Fox through Flamingo)
        tryReflection("com.android.tools.idea.gradle.project.model.AndroidModuleModel", module)
            ?.let { return it }

        // Try 3: Parse build.gradle / build.gradle.kts via PSI
        return parseBuildFileForFlavors(module)
    }

    private fun tryReflection(className: String, module: Module): Collection<String>? {
        return try {
            val clazz = Class.forName(className)
            val getMethod = clazz.getMethod("get", Module::class.java)
            val model = getMethod.invoke(null, module) ?: return null
            val flavorsMethod = model.javaClass.getMethod("getProductFlavorNames")
            @Suppress("UNCHECKED_CAST")
            val result = (flavorsMethod.invoke(model) as? Collection<String>)
            result?.takeIf { it.isNotEmpty() }
        } catch (e: ClassNotFoundException) {
            null
        } catch (e: NoSuchMethodException) {
            LOG.warn("Reflection: method not found on $className - ${e.message}")
            null
        } catch (e: Throwable) {
            LOG.warn("Reflection: ${e.javaClass.simpleName} for ${className.substringAfterLast('.')} on module '${module.name}' - ${e.message}")
            null
        }
    }

    // --- PSI-based parsing fallback ---

    private fun parseBuildFileForFlavors(module: Module): Collection<String> {
        val flavors = mutableSetOf<String>()

        for (root in ModuleRootManager.getInstance(module).contentRoots) {
            root.findChild("build.gradle")?.let { vf ->
                try {
                    flavors.addAll(ReadAction.compute<Set<String>, Throwable> { parseGroovyBuildFile(module, vf) })
                    flavors.addAll(ReadAction.compute<Set<String>, Throwable> { parseAppliedScripts(module, vf, root) })
                } catch (_: Throwable) { }
            }

            root.findChild("build.gradle.kts")?.let { vf ->
                try {
                    flavors.addAll(ReadAction.compute<Set<String>, Throwable> { parseKotlinBuildFile(module, vf) })
                } catch (_: Throwable) { }
            }
        }

        return flavors
    }

    // --- Groovy parsing ---

    private fun parseGroovyBuildFile(module: Module, vf: VirtualFile): Set<String> {
        val psiFile = PsiManager.getInstance(module.project).findFile(vf) as? GroovyFile ?: return emptySet()
        return extractFlavorsFromGroovyFile(psiFile)
    }

    private fun extractFlavorsFromGroovyFile(psiFile: GroovyFile): Set<String> {
        val flavors = mutableSetOf<String>()

        for (call in PsiTreeUtil.collectElementsOfType(psiFile, GrMethodCallExpression::class.java)) {
            if (call.invokedExpression.text == "productFlavors") {
                val closureArgs = call.closureArguments
                if (closureArgs.isNotEmpty()) {
                    PsiTreeUtil.getChildrenOfType(closureArgs.first(), GrMethodCall::class.java)
                        ?.forEach { flavorCall ->
                            val methodName = flavorCall.invokedExpression.text
                            if (methodName != null && methodName !in GROOVY_BUILTIN_METHODS) {
                                flavors.add(methodName)
                            }
                        }
                }
            }
        }

        return flavors
    }

    private fun parseAppliedScripts(module: Module, buildFile: VirtualFile, moduleRoot: VirtualFile): Set<String> {
        val psiFile = PsiManager.getInstance(module.project).findFile(buildFile) as? GroovyFile ?: return emptySet()
        val flavors = mutableSetOf<String>()

        for (call in PsiTreeUtil.collectElementsOfType(psiFile, GrMethodCallExpression::class.java)) {
            if (call.invokedExpression.text == "apply") {
                for (arg in call.namedArguments) {
                    if (arg.labelName == "from") {
                        val value = (arg.expression as? GrLiteral)?.value as? String ?: continue
                        val scriptFile = moduleRoot.findFileByRelativePath(value) ?: continue
                        if (scriptFile.extension == "gradle") {
                            val scriptPsi = PsiManager.getInstance(module.project).findFile(scriptFile) as? GroovyFile
                                ?: continue
                            flavors.addAll(extractFlavorsFromGroovyFile(scriptPsi))
                        }
                    }
                }
            }
        }

        return flavors
    }

    // --- Kotlin DSL parsing ---

    private fun parseKotlinBuildFile(module: Module, vf: VirtualFile): Set<String> {
        val psiFile = PsiManager.getInstance(module.project).findFile(vf) as? KtFile ?: return emptySet()
        val flavors = mutableSetOf<String>()

        for (call in PsiTreeUtil.collectElementsOfType(psiFile, KtCallExpression::class.java)) {
            val callName = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
            if (callName == "productFlavors") {
                val lambda = call.lambdaArguments.firstOrNull()?.getLambdaExpression()
                    ?: (call.valueArguments.lastOrNull()?.getArgumentExpression() as? KtLambdaExpression)
                    ?: continue

                val body = lambda.bodyExpression ?: continue

                for (innerCall in PsiTreeUtil.collectElementsOfType(body, KtCallExpression::class.java)) {
                    val innerName = (innerCall.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
                    if (innerName in KOTLIN_DSL_FLAVOR_METHODS) {
                        val firstArg = innerCall.valueArguments.firstOrNull()?.getArgumentExpression()
                        val flavorName = (firstArg as? KtStringTemplateExpression)?.entries?.firstOrNull()?.text
                        if (flavorName != null && flavorName.isNotBlank()) {
                            flavors.add(flavorName)
                        }
                    }
                }
            }
        }

        return flavors
    }

    companion object {
        private val KOTLIN_DSL_FLAVOR_METHODS = setOf("create", "register", "getByName")
        private val GROOVY_BUILTIN_METHODS = setOf(
            "dimension", "flavorDimensions", "setFlavorDimensions",
            "applicationId", "minSdk", "minSdkVersion",
            "targetSdk", "targetSdkVersion", "versionCode",
            "versionName", "testInstrumentationRunner",
            "buildConfigField", "resValue", "proguardFiles",
            "signingConfig", "matchingFallbacks", "create",
            "register", "getByName", "all", "configureEach",
            "named", "withType"
        )
    }
}
