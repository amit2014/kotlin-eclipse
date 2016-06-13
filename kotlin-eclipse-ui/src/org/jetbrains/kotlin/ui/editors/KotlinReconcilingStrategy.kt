/*******************************************************************************
* Copyright 2000-2015 JetBrains s.r.o.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
*******************************************************************************/
package org.jetbrains.kotlin.ui.editors

import org.eclipse.core.resources.IFile
import org.eclipse.core.runtime.ISafeRunnable
import org.eclipse.jdt.core.IJavaProject
import org.eclipse.jdt.core.JavaCore
import org.eclipse.jface.text.IDocument
import org.eclipse.jface.text.IRegion
import org.eclipse.jface.text.reconciler.DirtyRegion
import org.eclipse.jface.text.reconciler.IReconcilingStrategy
import org.eclipse.jface.util.SafeRunnable
import org.eclipse.swt.widgets.Display
import org.eclipse.ui.views.contentoutline.IContentOutlinePage
import org.jetbrains.kotlin.core.builder.KotlinPsiManager
import org.jetbrains.kotlin.core.log.KotlinLogger
import org.jetbrains.kotlin.core.model.KotlinAnalysisFileCache
import org.jetbrains.kotlin.core.model.KotlinAnalysisProjectCache
import org.jetbrains.kotlin.core.resolve.KotlinAnalyzer
import org.jetbrains.kotlin.eclipse.ui.utils.EditorUtil
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.diagnostics.Diagnostics
import org.jetbrains.kotlin.ui.editors.outline.KotlinOutlinePage
import org.jetbrains.kotlin.ui.editors.annotations.DiagnosticAnnotationUtil

interface KotlinReconcilingListener {
    fun reconcile(file: IFile, editor: KotlinEditor)
}

class KotlinReconcilingStrategy(val editor: KotlinEditor) : IReconcilingStrategy {
    private val reconcilingListeners = hashSetOf<KotlinReconcilingListener>()
    
    fun addListener(listener: KotlinReconcilingListener) {
        reconcilingListeners.add(listener)
    }
    
    fun removeListener(listener: KotlinReconcilingListener) {
        reconcilingListeners.remove(listener)
    }
    
    override fun setDocument(document: IDocument?) {}
    
    override fun reconcile(dirtyRegion: DirtyRegion?, subRegion: IRegion?) {}
    
    override fun reconcile(partition: IRegion?) {
        SafeRunnable.run(object : ISafeRunnable {
            override fun run() {
                val file = editor.eclipseFile
                if (file != null) {
                    resetCache(file)
                    KotlinPsiManager.getKotlinFileIfExist(file, editor.document.get()) // commit file
                    
                    reconcilingListeners.forEach { it.reconcile(file, editor) }
                } else {
                    KotlinLogger.logError("Failed to retrieve IFile from editor $editor", null)
                }
            }
            
            override fun handleException(exception: Throwable) {
                KotlinLogger.logError(exception)
            }
        })
    }
    
    private fun resetCache(file: IFile) {
        val javaProject = JavaCore.create(file.getProject())
        KotlinAnalysisProjectCache.resetCache(javaProject)
        KotlinAnalysisFileCache.resetCache()
    }
}