package org.jetbrains.kotlin.ui.editors.formatter

import org.eclipse.jface.text.IDocument
import com.intellij.psi.codeStyle.CodeStyleSettings
import org.jetbrains.kotlin.idea.core.formatter.KotlinCodeStyleSettings
import org.jetbrains.kotlin.ui.formatter.KotlinBlock
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.ui.formatter.NullAlignmentStrategy
import com.intellij.formatting.Indent
import org.jetbrains.kotlin.idea.common.formatter.createSpacingBuilder
import org.jetbrains.kotlin.eclipse.ui.utils.LineEndUtil
import com.intellij.formatting.Indent.Type
import com.intellij.formatting.ASTBlock
import com.intellij.formatting.Block

val settings = CodeStyleSettings(true)
val kotlinSettings = settings.getCustomSettings(KotlinCodeStyleSettings::class.java)

fun computeAlignment(ktFile: KtFile, offset: Int): Int {
    val rootBlock = KotlinBlock(ktFile.node, 
                NullAlignmentStrategy(), 
                Indent.getNoneIndent(), 
                null,
                settings,
                createSpacingBuilder(settings))
    
    val (block, indent) = computeBlocks(rootBlock, offset)
    
    val attributes = block.getChildAttributes(1)
    return if (attributes.childIndent?.type == Type.NORMAL) {
        indent + 1
    } else {
        indent
    }
}

fun computeBlocks(root: KotlinBlock, offset: Int): BlockWithIndentation {
    var indent = 0
    var rootBlock: Block = root
    
    while (true) {
        val subBlocks = rootBlock.getSubBlocks()
        var narrowBlock: Block? = null
        for (subBlock in subBlocks) {
            if (offset in subBlock.getTextRange()) {
                narrowBlock = subBlock
                break
            }
        }
        
        if (narrowBlock != null && !narrowBlock.isLeaf) {
            if (narrowBlock.indent?.type == Type.NORMAL) {
                indent++
            }
            rootBlock = narrowBlock
        } else {
            break
        }
    }
    
    return BlockWithIndentation(rootBlock, indent)
}

data class BlockWithIndentation(val block: Block, val indent: Int)