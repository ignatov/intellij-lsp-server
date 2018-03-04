package com.ruin.lsp.commands.hover

import com.github.kittinunf.result.Result
import com.intellij.codeInsight.documentation.DocumentationManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiFile
import com.ruin.lsp.util.withEditor
import com.ruin.lsp.commands.Command
import org.eclipse.lsp4j.*
import java.util.concurrent.CompletableFuture

class HoverCommand(val textDocumentIdentifier: TextDocumentIdentifier,
                   val position: Position) : Command<MarkedString>, Disposable {
    override fun execute(project: Project, file: PsiFile): CompletableFuture<MarkedString> {
        return CompletableFuture.supplyAsync {
            val ref: Ref<String> = Ref()
            withEditor(this, file, position) {editor ->
                val originalElement = file.findElementAt(editor.caretModel.offset)

                val element = DocumentationManager.getInstance(project).findTargetElement(editor, file)

                val result = if (element != null) {
                    // TODO: might want to use something like CtrlMouseHandler instead
                    try {
                        HoverDocumentationProvider().generateDoc(element, originalElement) ?: ""
                    } catch (ex: IndexNotReadyException) {
                        ""
                    }
                } else {
                    ""
                }
                ref.set(result)
            }

            val language = "java"

            MarkedString(language, ref.get())
        }
    }
}
