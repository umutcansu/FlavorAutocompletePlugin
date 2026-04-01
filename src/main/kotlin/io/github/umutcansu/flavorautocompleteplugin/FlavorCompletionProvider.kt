package io.github.umutcansu.flavorautocompleteplugin

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.literals.GrLiteral
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

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
            LOG.warn("InsertHandler: Kotlin '$' state detected. Modifying only the '$'.")
            context.document.replaceString(elementAtCaret.textRange.startOffset, elementAtCaret.textRange.endOffset, item.lookupString)
            return@InsertHandler
        }

        val stringLiteral = PsiTreeUtil.getParentOfType(
            elementAtCaret,
            GrLiteral::class.java,
            KtStringTemplateExpression::class.java
        )

        if (stringLiteral != null) {
            LOG.warn("InsertHandler: Inside a string literal. Using REPLACE behavior.")
            val textRange = stringLiteral.textRange
            val newText = "\"${item.lookupString}\""
            context.document.replaceString(textRange.startOffset, textRange.endOffset, newText)
            context.editor.caretModel.moveToOffset(textRange.startOffset + newText.length)
        } else {
            LOG.warn("InsertHandler: In a normal code area. Using MODIFY behavior.")
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
        LOG.warn("!!! addCompletions METHOD CALLED (Searching ALL Modules with Caching) !!!")

        val flavors = findFlavorsFromAllModules(parameters)

        if (flavors.isNotEmpty()) {
            LOG.warn("Flavors found in project: ${flavors.joinToString()}")
            val customResultSet = resultSet.withPrefixMatcher(CustomPrefixMatcher(resultSet.prefixMatcher.prefix))

            flavors.forEach { flavorName ->
                val lookupElement = LookupElementBuilder.create(flavorName)
                    .withInsertHandler(smartInsertHandler)
                customResultSet.addElement(lookupElement)
            }
        } else {
            LOG.warn("No flavors could be found in any module of the project.")
        }
    }

    private fun findFlavorsFromAllModules(parameters: CompletionParameters): Set<String> {
        val project = parameters.originalFile.project

        return CachedValuesManager.getManager(project).getCachedValue(project) {
            LOG.warn("CACHE MISS: Recalculating all flavors for the project.")

            val allModules = ModuleManager.getInstance(project).modules
            val allFlavors = mutableSetOf<String>()

            for (module in allModules) {
                try {
                    val flavors = getProductFlavorNamesViaReflection(module)
                    if (flavors.isNotEmpty()) {
                        LOG.warn("Found Android module '${module.name}', flavors: ${flavors.joinToString()}")
                        allFlavors.addAll(flavors)
                    }
                } catch (e: Throwable) {
                    LOG.warn("Could not get flavors for module '${module.name}': ${e.message}")
                }
            }
            CachedValueProvider.Result.create(
                allFlavors,
                ProjectRootManager.getInstance(project)
            )
        }
    }

    private fun getProductFlavorNamesViaReflection(module: Module): Collection<String> {
        return try {
            val modelClass = Class.forName("com.android.tools.idea.gradle.project.model.GradleAndroidModel")
            val getMethod = modelClass.getMethod("get", Module::class.java)
            val model = getMethod.invoke(null, module) ?: return emptyList()
            val flavorsMethod = model.javaClass.getMethod("getProductFlavorNames")
            @Suppress("UNCHECKED_CAST")
            (flavorsMethod.invoke(model) as? Collection<String>) ?: emptyList()
        } catch (e: ClassNotFoundException) {
            emptyList()
        } catch (e: NoSuchMethodException) {
            emptyList()
        } catch (e: Throwable) {
            emptyList()
        }
    }
}
