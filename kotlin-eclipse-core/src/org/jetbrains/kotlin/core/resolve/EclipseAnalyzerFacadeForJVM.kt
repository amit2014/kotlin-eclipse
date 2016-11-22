/*******************************************************************************
* Copyright 2000-2016 JetBrains s.r.o.
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
package org.jetbrains.kotlin.core.resolve

import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.cli.jvm.compiler.CliLightClassGenerationSupport
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.container.ComponentProvider
import org.jetbrains.kotlin.core.log.KotlinLogger
import org.jetbrains.kotlin.core.model.KotlinEnvironment
import org.jetbrains.kotlin.core.model.KotlinScriptEnvironment
import org.jetbrains.kotlin.core.utils.ProjectUtils
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PackageFragmentProvider
import org.jetbrains.kotlin.descriptors.impl.CompositePackageFragmentProvider
import org.jetbrains.kotlin.descriptors.impl.ModuleDependenciesImpl
import org.jetbrains.kotlin.frontend.java.di.initJvmBuiltInsForTopDownAnalysis
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.LazyTopDownAnalyzer
import org.jetbrains.kotlin.resolve.TopDownAnalysisMode
import org.jetbrains.kotlin.resolve.jvm.JavaDescriptorResolver
import org.jetbrains.kotlin.resolve.jvm.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.resolve.jvm.TopDownAnalyzerFacadeForJVM.SourceOrBinaryModuleClassResolver
import org.jetbrains.kotlin.resolve.jvm.extensions.PackageFragmentProviderExtension
import org.jetbrains.kotlin.resolve.lazy.KotlinCodeAnalyzer
import org.jetbrains.kotlin.resolve.lazy.declarations.FileBasedDeclarationProviderFactory
import org.jetbrains.kotlin.util.KotlinFrontEndException
import java.util.ArrayList
import java.util.LinkedHashSet
import org.jetbrains.kotlin.frontend.java.di.createContainerForTopDownAnalyzerForJvm as createContainerForScript

public data class AnalysisResultWithProvider(val analysisResult: AnalysisResult, val componentProvider: ComponentProvider)

public object EclipseAnalyzerFacadeForJVM {
    public fun analyzeFilesWithJavaIntegration(
            environment: KotlinEnvironment,
            filesToAnalyze: Collection<KtFile>): AnalysisResultWithProvider {
        val filesSet = filesToAnalyze.toSet()
        if (filesSet.size != filesToAnalyze.size) {
            KotlinLogger.logWarning("Analyzed files have duplicates")
        }
        
        val allFiles = LinkedHashSet<KtFile>(filesSet)
        val addedFiles = filesSet.map { getPath(it) }.filterNotNull().toSet()
        ProjectUtils.getSourceFilesWithDependencies(environment.javaProject).filterNotTo(allFiles) {
            getPath(it) in addedFiles
        }
        
        val project = environment.project
        val moduleContext = TopDownAnalyzerFacadeForJVM.createContextWithSealedModule(project, environment.configuration)
        val providerFactory = FileBasedDeclarationProviderFactory(moduleContext.storageManager, allFiles)
        val trace = CliLightClassGenerationSupport.CliBindingTrace()
        
        val scope = GlobalSearchScope.allScope(project)
        val moduleClassResolver = SourceOrBinaryModuleClassResolver(scope)

        val languageVersionSettings = LanguageVersionSettingsImpl.DEFAULT
        val module = moduleContext.module
        val storageManager = moduleContext.storageManager
        
        val container = createContainerForTopDownAnalyzerForJvm(
                moduleContext,
                trace,
                providerFactory,
                scope,
                LookupTracker.DO_NOTHING,
                KotlinPackagePartProvider(environment),
                languageVersionSettings,
                moduleClassResolver,
                environment.javaProject).apply {
            initJvmBuiltInsForTopDownAnalysis(module, languageVersionSettings)
        }
        
        moduleClassResolver.sourceCodeResolver = container.get<JavaDescriptorResolver>()
        moduleClassResolver.compiledCodeResolver = container.get<JavaDescriptorResolver>()
        
        val additionalProviders = ArrayList<PackageFragmentProvider>()
        additionalProviders.add(container.get<JavaDescriptorResolver>().packageFragmentProvider)
        
        PackageFragmentProviderExtension.getInstances(project).mapNotNullTo(additionalProviders) { extension ->
            extension.getPackageFragmentProvider(project, module, storageManager, trace, null)
        }
        
        module.setDependencies(ModuleDependenciesImpl(
                listOf(module),
                emptySet()
        ))
        module.initialize(CompositePackageFragmentProvider(
                listOf(container.get<KotlinCodeAnalyzer>().packageFragmentProvider) +
                additionalProviders
        ))
        
        
        try {
            container.get<LazyTopDownAnalyzer>().analyzeDeclarations(TopDownAnalysisMode.TopLevelDeclarations, filesSet)
        } catch(e: KotlinFrontEndException) {
//          Editor will break if we do not catch this exception
//          and will not be able to save content without reopening it.
//          In IDEA this exception throws only in CLI
            KotlinLogger.logError(e)
        }
        
        return AnalysisResultWithProvider(
                AnalysisResult.success(trace.getBindingContext(), module),
                container)
    }
    
    public fun analyzeScript(
            environment: KotlinScriptEnvironment,
            scriptFile: KtFile): AnalysisResultWithProvider {
        
        val trace = CliLightClassGenerationSupport.CliBindingTrace()
        
        val configuration = CompilerConfiguration().apply {
            put(CommonConfigurationKeys.MODULE_NAME, scriptFile.name)
        }
        
        val container = TopDownAnalyzerFacadeForJVM.createContainer(
                environment.project,
                setOf(scriptFile),
                trace,
                configuration,
                { KotlinPackagePartProvider(environment) },
                { storageManager, files -> FileBasedDeclarationProviderFactory(storageManager, files) }
        )
        
        try {
            container.get<LazyTopDownAnalyzer>().analyzeDeclarations(TopDownAnalysisMode.TopLevelDeclarations, setOf(scriptFile))
        } catch(e: KotlinFrontEndException) {
//          Editor will break if we do not catch this exception
//          and will not be able to save content without reopening it.
//          In IDEA this exception throws only in CLI
            KotlinLogger.logError(e)
        }
        
        return AnalysisResultWithProvider(
                AnalysisResult.success(trace.getBindingContext(), container.get<ModuleDescriptor>()),
                container)
    }
    
    private fun getPath(jetFile: KtFile): String? = jetFile.getVirtualFile()?.getPath()
}