package com.ruin.lsp.commands.document.find

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.ProjectScope.getProjectScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.SuperMethodsSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.containers.ContainerUtil
import com.ruin.lsp.commands.DocumentCommand
import com.ruin.lsp.commands.ExecutionContext
import com.ruin.lsp.model.LanguageServerException
import com.ruin.lsp.util.getDocument
import com.ruin.lsp.util.location
import com.ruin.lsp.util.toOffset
import com.ruin.lsp.util.withEditor
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.caches.resolve.unsafeResolveToDescriptor
import org.jetbrains.kotlin.idea.core.getDirectlyOverriddenDeclarations
import org.jetbrains.kotlin.idea.search.ideaExtensions.KotlinDefinitionsSearcher
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.resolve.lazy.NoDescriptorForDeclarationException

class FindDefinitionCommand(val position: Position) : DocumentCommand<MutableList<Location>> {
    override fun execute(ctx: ExecutionContext): MutableList<Location> {
        val doc = getDocument(ctx.file)
            ?: throw LanguageServerException("No document found.")

        val offset = position.toOffset(doc)

        val list = findDefinitionByReference(ctx, offset)
        if(list != null) {
            return list
        }

        return when (ctx.file.fileType) {
            is KotlinFileType -> findDefinitionForKotlin(ctx, offset)
            is JavaFileType -> forDefinitionForJava(ctx, offset)
            else -> mutableListOf()
        }
    }

    private fun findDefinitionByReference(ctx: ExecutionContext, offset: Int): MutableList<Location>? {
        var loc: MutableList<Location>? = null
        withEditor(this, ctx.file, offset) { editor ->
            val targetElements = GotoDeclarationAction.findTargetElementsNoVS(ctx.project, editor, offset, false)
            val results = targetElements?.mapNotNull(PsiElement::sourceLocationIfPossible)
            loc = if (results?.isNotEmpty() == true) {
                results.toMutableList()
            } else {
                null
            }
        }
        return loc
    }

    private fun forDefinitionForJava(ctx: ExecutionContext, offset: Int): MutableList<Location> {
        var lookup: PsiElement? = null
        val element = ctx.file.findElementAt(offset)
        val parent = element?.parent
        if (parent != null && parent is PsiMethod) {
            val superSignature =
                SuperMethodsSearch.search(parent, null, true, false).findFirst()
            lookup = superSignature?.method
        }

        return lookup?.location()?.let { mutableListOf(it) }
            ?: mutableListOf()
    }

    private fun findDefinitionForKotlin(ctx: ExecutionContext, offset: Int): MutableList<Location> {
        try {
            var list = findKotlinDefinitionBySearcher(ctx, offset)
            if(list != null) {
                return list
            }

            list = findKotlinSuperMethod(ctx, offset)
            if(list != null) {
                return list
            }

            return mutableListOf()
        } catch (e: NoDescriptorForDeclarationException) {
            return mutableListOf()
        }
    }

    private fun findKotlinDefinitionBySearcher(ctx: ExecutionContext, offset: Int): MutableList<Location>? {
        val elt = ctx.file.findElementAt(offset)
        if(elt != null) {
            val results: MutableList<Location> = mutableListOf()
            KotlinDefinitionsSearcher().execute(DefinitionsScopedSearch.SearchParameters(elt, getProjectScope(ctx.project), true)) {
                val resolved = when (it) {
                    is PsiClass -> it.nameIdentifier ?: it
                    else -> it
                }

                results.push(resolved.sourceLocationIfPossible())
            }
            if (results.isNotEmpty()) {
                return results
            }
        }
        return null
    }

    private fun findKotlinSuperMethod(ctx: ExecutionContext, offset: Int): MutableList<Location>? {
        val elt = ctx.file.findElementAt(offset) ?: return null
        val declaration = PsiTreeUtil.getParentOfType<PsiElement>(elt,
            KtNamedFunction::class.java,
            KtClass::class.java,
            KtProperty::class.java,
            KtObjectDeclaration::class.java) as KtDeclaration? ?: return null
        try {
            val descriptor = declaration.unsafeResolveToDescriptor(BodyResolveMode.PARTIAL)
            val superDeclarations = findSuperDeclarations(descriptor) ?: return null
            return superDeclarations.map(PsiElement::sourceLocationIfPossible).toMutableList()
        } catch (e: IndexNotReadyException) {
            return mutableListOf()
        }
    }

    // copied from GotoSuperActionHandler
    private fun findSuperDeclarations(descriptor: DeclarationDescriptor): List<PsiElement>? {
        val superDescriptors: Collection<Any?>
        superDescriptors = if (descriptor is ClassDescriptor) {
            val supertypes = descriptor.typeConstructor.supertypes
            val superclasses = ContainerUtil.mapNotNull(supertypes) { type ->
                val declarationDescriptor = type.constructor.declarationDescriptor
                declarationDescriptor as? ClassDescriptor
            }
            ContainerUtil.removeDuplicates(superclasses)
            superclasses
        } else {
            if (descriptor !is CallableMemberDescriptor) {
                return null
            }

            descriptor.getDirectlyOverriddenDeclarations()
        }

        return superDescriptors.mapNotNull { desc ->
            if (desc is ClassDescriptor && KotlinBuiltIns.isAny(desc))
                null
            else
                DescriptorToSourceUtils.descriptorToDeclaration(desc as DeclarationDescriptor)
        }
    }
}
