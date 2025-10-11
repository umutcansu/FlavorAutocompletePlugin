package io.github.umutcansu.flavorautocompleteplugin

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.patterns.PlatformPatterns.psiElement

class FlavorCompletionContributor : CompletionContributor() {

    private val LOG = Logger.getInstance(FlavorCompletionContributor::class.java)

    init {
        LOG.warn("!!! FlavorCompletionContributor RUNNING !!!")

        extend(
            CompletionType.BASIC,
            psiElement(),
            FlavorCompletionProvider()
        )
    }
}