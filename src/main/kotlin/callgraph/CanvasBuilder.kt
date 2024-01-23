package callgraph

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressIndicatorProvider
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameterList
import java.io.File


class CanvasBuilder {
    private val fileModifiedTimeCache = mutableMapOf<PsiFile, Long>()

    private var progressIndicator: ProgressIndicator? = null
    private var dependenciesCache = emptySet<Dependency>()
    var LOGGER = com.intellij.openapi.diagnostic.Logger.getInstance(CanvasBuilder::class.java)
    fun build(canvasConfig: CanvasConfig) {
        // cancel existing progress if any
        this.progressIndicator?.cancel()
        this.progressIndicator = ProgressIndicatorProvider.getGlobalProgressIndicator()

        LOGGER.debug("build call 1")

        var file = File("/Users/cocui/i100/dependencies.txt")

        if (file.exists()) {
            file.delete()
        } else {
            file.createNewFile()
        }
        // build a dependency snapshot for the entire code base
        val dependencies = getDependencies(canvasConfig, this.dependenciesCache, this.fileModifiedTimeCache)

        file.appendText("all dependencies ---> :")

        for ((index, dependency) in dependencies.withIndex()) {
            var method_abs_info = ""
            method_abs_info += "Caller: "+getMethodInfo(dependency.caller)
            method_abs_info += "\n"
            method_abs_info += "Callee: "+getMethodInfo(dependency.callee)
            file.appendText("\n")
            file.appendText("\n")
            file.appendText(method_abs_info)
        }
        file.appendText("\n")
        file.appendText("all dependencies <--- :")

        // visualize the viewing part as graph
        val sourceCodeRoots = Utils.getSourceCodeRoots(canvasConfig)
        file.appendText("\n")

        file.appendText("all sourceCodeRoots ---> :")

        for ((index, sourceCodeRoot) in sourceCodeRoots.withIndex()) {
            file.appendText("\n")
            file.appendText(sourceCodeRoot.path)
        }
        file.appendText("\n")

        file.appendText("all sourceCodeRoots <--- :")

        val files = Utils.getSourceCodeFiles(canvasConfig.project, sourceCodeRoots)
        file.appendText("\n")

        file.appendText("all files ---> :")

        for ((index, filep) in files.withIndex()) {
            file.appendText("\n")
            file.appendText(filep.name)
        }
        file.appendText("all files <--- :")

        val methods = Utils.getMethodsInScope(canvasConfig, files)
        LOGGER.debug("build call 1" + file.exists())

        LOGGER.debug("build call 2")
        val dependencyView = Utils.getDependencyView(canvasConfig, methods, dependencies)
        val graph = buildGraph(methods, dependencyView)
        canvasConfig.canvas.reset(graph)
    }

    private fun getMethodInfo(method: PsiMethod): String {
        var method_abs_info :StringBuilder= StringBuilder()
        method_abs_info.append(method.containingClass.toString())
        method_abs_info.append( "-" + method.name)
        method_abs_info.append("(")
        // 获取参数列表
        val parameterList: PsiParameterList = method.getParameterList()

        var parameter_info :StringBuilder= StringBuilder()
        for (parameter in parameterList.parameters) {
            val parameterName = parameter.name
            val parameterType = parameter.type.presentableText
            parameter_info.append(parameterType + " " + parameterName + ",")
        }
        if (parameter_info.length > 0){
            parameter_info.deleteCharAt(parameter_info.length-1)
        }
        method_abs_info.append(parameter_info)
        method_abs_info.append(")")
        return method_abs_info.toString();
    }

    private fun buildGraph(methods: Set<PsiMethod>, dependencyView: Set<Dependency>): Graph {
        val graph = Graph()
        methods.forEach { graph.addNode(it) }
        dependencyView.forEach {
            graph.addNode(it.caller)
            graph.addNode(it.callee)
            graph.addEdge(it.caller, it.callee)
        }
        Utils.layout(graph)
        return graph
    }

    private fun getDependencies(
            canvasConfig: CanvasConfig,
            dependenciesCache: Set<Dependency>,
            fileModifiedTimeCache: Map<PsiFile, Long>
    ): Set<Dependency> {
        val allFiles = Utils.getAllSourceCodeFiles(canvasConfig.project)
        val newFiles = allFiles.filter { !fileModifiedTimeCache.containsKey(it) }
        val changedFiles = allFiles
                .filter { fileModifiedTimeCache.containsKey(it) && fileModifiedTimeCache[it] != it.modificationStamp }
                .toSet()
        val validDependencies = dependenciesCache
                .filter {
                    !changedFiles.contains(it.caller.containingFile) && !changedFiles.contains(it.callee.containingFile)
                }
                .toSet()
        val invalidFiles = dependenciesCache
                .filter { !validDependencies.contains(it) }
                .flatMap { listOf(it.caller.containingFile, it.callee.containingFile) }
                .toSet()
        val filesToParse = newFiles.union(invalidFiles)
        val methodsToParse = Utils.getMethodsFromFiles(filesToParse)

        // parse method dependencies
        canvasConfig.callGraphToolWindow.resetProgressBar(methodsToParse.size)
        val newDependencies = methodsToParse
                .flatMap {
                    canvasConfig.callGraphToolWindow.incrementProgressBar()
                    Utils.getDependenciesFromMethod(it)
                }
                .toSet()
        val dependencies = validDependencies.union(newDependencies)

        // cache the dependencies for next use
        this.dependenciesCache = dependencies
        this.fileModifiedTimeCache.clear()
        this.fileModifiedTimeCache.putAll(allFiles.associateBy({ it }, { it.modificationStamp }))

        return dependencies
    }
}
